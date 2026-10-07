// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.SafeCloseable;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.PluginInfoConverter;
import software.amazon.lambda.durable.plugin.PluginRunner;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Orchestrates the lifecycle of a durable execution.
 *
 * <p>Handles deserialization of user input, invocation of the user handler within a {@link DurableContext}, and
 * production of the {@link DurableExecutionOutput} (success, failure, or pending suspension).
 */
public class DurableExecutor {
    private static final String ROOT_THREAD_ID = null;
    private static final Logger logger = LoggerFactory.getLogger(DurableExecutor.class);

    // Lambda response size limit is 6MB minus small epsilon for envelope
    private static final int LAMBDA_RESPONSE_SIZE_LIMIT = 6 * 1024 * 1024 - 50;

    private DurableExecutor() {}

    /**
     * Returns whether this core runs invocation start/end hooks on the root handler thread and awaits handler cleanup
     * and end-hook completion before returning. Plugins that retain thread-local scopes may require this capability
     * before construction. Introduced with the 2.2.2 lifecycle contract.
     */
    public static boolean supportsSameThreadInvocationHooks() {
        return true;
    }

    public static <I, O> DurableExecutionOutput execute(
            DurableExecutionInput input,
            Context lambdaContext,
            TypeToken<I> inputType,
            BiFunction<I, DurableContext, O> handler,
            DurableConfig config) {
        try (var manager = new ExecutionManager(input, config, lambdaContext)) {
            manager.registerActiveThread(ROOT_THREAD_ID);
            var invocation = new Invocation<>(input, lambdaContext, inputType, handler, config, manager);
            try {
                return invocation.execute().join();
            } catch (CompletionException failure) {
                ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(failure));
                return null;
            }
        }
    }

    /** Invocation-local state, accessed on the handler thread when plugins are present. */
    private static final class Invocation<I, O> {
        private final Context lambdaContext;
        private final TypeToken<I> inputType;
        private final BiFunction<I, DurableContext, O> handler;
        private final DurableConfig config;
        private final ExecutionManager manager;
        private final PluginRunner plugins;
        private final String requestId;
        private final String executionArn;
        private final boolean isFirstInvocation;
        private I userInput;
        private boolean started;

        private Invocation(
                DurableExecutionInput input,
                Context lambdaContext,
                TypeToken<I> inputType,
                BiFunction<I, DurableContext, O> handler,
                DurableConfig config,
                ExecutionManager manager) {
            this.lambdaContext = lambdaContext;
            this.inputType = inputType;
            this.handler = handler;
            this.config = config;
            this.manager = manager;
            plugins = config.getPluginRunner();
            requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
            executionArn = input.durableExecutionArn();
            isFirstInvocation = !manager.isReplaying();
        }

        private CompletableFuture<DurableExecutionOutput> execute() {
            var body = new CompletableFuture<O>();
            // Select the invocation outcome before scheduling, including with an inline executor. A suspension or
            // termination can win while the handler is still unwinding; its finally block must not replace it.
            var outcome = manager.runUntilCompleteOrSuspend(body).handle(Outcome<O>::new);
            Supplier<DurableExecutionOutput> task = () -> {
                Outcome.capture(this::invokeHandler).complete(body);
                var selected = outcome.join();
                return finishInvocation(selected.value(), selected.failure());
            };
            // Cleanup is awaited even without plugins. Keep that path free of plugin MDC handling.
            if (plugins.isEmpty()) return CompletableFuture.supplyAsync(task, config.getExecutorService());
            return supplyHandler(task, failure -> finishInvocation(null, failure), config.getExecutorService());
        }

        private O invokeHandler() {
            manager.setCurrentThreadContext(new ThreadContext(ROOT_THREAD_ID, ThreadType.CONTEXT));
            Throwable inputFailure = null;
            try {
                userInput = extractUserInput(manager.getExecutionOperation(), config.getSerDes(), inputType);
            } catch (Throwable failure) {
                // Deserialize only once. Even failed input gets paired start/end hooks with a null input value.
                inputFailure = failure;
            }
            fireOnInvocationStart();
            if (inputFailure != null) ExceptionHelper.sneakyThrow(inputFailure);
            var context = DurableContextImpl.createRootContext(manager, config, lambdaContext);
            DurableContextImpl.setCurrentContext(context);
            try (var ignored = DurableLogger.attachContext()) {
                return handler.apply(userInput, context);
            }
        }

        private void fireOnInvocationStart() {
            if (plugins.isEmpty()) return;
            var info = new InvocationInfo(
                    requestId,
                    executionArn,
                    isFirstInvocation,
                    manager.getExecutionOperation().startTimestamp(),
                    userInput,
                    PluginInfoConverter.toOperationItemMap(
                            manager.getOperationsSnapshot(), manager.getInitialOperationIds()),
                    PluginInfoConverter.toOperationItemMap(
                            manager.getUpdatedOperationsSnapshot(), manager.getInitialOperationIds()));
            started = true;
            plugins.onInvocationStart(info);
        }

        private DurableExecutionOutput finishInvocation(O value, Throwable failure) {
            if (failure != null) return finishFailure(ExceptionHelper.unwrapCompletableFuture(failure));
            DurableExecutionOutput output;
            try {
                var payload = config.getSerDes().serialize(value);
                output = DurableExecutionOutput.success(handleLargePayload(manager, payload));
            } catch (Throwable deliveryFailure) {
                // Result serialization/checkpointing also belongs to this invocation. Close plugin resources even
                // when delivery fails, then retain the original exception for the Lambda caller.
                var cause = ExceptionHelper.unwrapCompletableFuture(deliveryFailure);
                // No terminal output is delivered: this exception escapes for a Lambda invocation retry.
                fireOnInvocationEnd(InvocationStatus.RETRYING, cause, null);
                ExceptionHelper.sneakyThrow(deliveryFailure);
                return null;
            }
            fireOnInvocationEnd(InvocationStatus.SUCCEEDED, null, value);
            return output;
        }

        private DurableExecutionOutput finishFailure(Throwable cause) {
            var status = failureStatus(cause);
            fireOnInvocationEnd(status, status == InvocationStatus.PENDING ? null : cause, null);
            if (status == InvocationStatus.PENDING) return DurableExecutionOutput.pending();
            if (status == InvocationStatus.RETRYING) {
                ExceptionHelper.sneakyThrow(cause);
                return null;
            }
            return DurableExecutionOutput.failure(buildErrorObject(cause, config.getSerDes()));
        }

        private void fireOnInvocationEnd(InvocationStatus status, Throwable error, Object result) {
            if (!started) return;
            plugins.onInvocationEnd(new InvocationEndInfo(
                    requestId,
                    executionArn,
                    isFirstInvocation,
                    manager.getExecutionOperation().startTimestamp(),
                    PluginInfoConverter.toOperationItemMap(
                            manager.getOperationsSnapshot(), manager.getInitialOperationIds()),
                    status,
                    error,
                    userInput,
                    result));
        }
    }

    private static InvocationStatus failureStatus(Throwable failure) {
        if (failure instanceof SuspendExecutionException) return InvocationStatus.PENDING;
        if (failure instanceof UnrecoverableDurableExecutionException unrecoverable && unrecoverable.isRetryable()) {
            return InvocationStatus.RETRYING;
        }
        return InvocationStatus.FAILED;
    }

    /** Captures task completion without running lifecycle hooks on CompletableFuture completion threads. */
    private record Outcome<T>(T value, Throwable failure) {
        private static <T> Outcome<T> capture(Supplier<T> task) {
            try {
                return new Outcome<>(task.get(), null);
            } catch (Throwable failure) {
                return new Outcome<>(null, failure);
            }
        }

        private void complete(CompletableFuture<T> future) {
            if (failure == null) future.complete(value);
            else future.completeExceptionally(failure);
        }
    }

    private static <T> CompletableFuture<T> supplyHandler(
            Supplier<T> task, Function<Throwable, T> initializationFailure, Executor executor) {
        var result = new CompletableFuture<T>();
        Runnable work = (Runnable & CompletableFuture.AsynchronousCompletionTask) () -> {
            SafeCloseable restore;
            try {
                restore = restoreMdcOnClose();
            } catch (Throwable failure) {
                Outcome.capture(() -> initializationFailure.apply(failure)).complete(result);
                return;
            }
            var outcome = Outcome.capture(task);
            try {
                try (var ignored = restore) {
                    // End/delivery failures must also escape their actual worker. Handler-body failures have
                    // already been mapped to their selected durable outcome; this does not reclassify them.
                    rethrowLifecycleFatal(outcome.failure());
                }
            } finally {
                // End and worker restoration have run before publishing. A restoration failure still escapes its
                // owner, without changing the invocation outcome already delivered to the end hooks.
                outcome.complete(result);
            }
        };
        try {
            executor.execute(work);
        } catch (RuntimeException dispatchFailure) {
            if (!result.isDone()) throw dispatchFailure;
            // Inline execution can throw from MDC restoration after settling the selected outcome. Preserve that
            // outcome for an ordinary cleanup failure, while a JVM-fatal failure still reaches the caller.
            rethrowLifecycleFatal(dispatchFailure);
        }
        return result;
    }

    /** Inspects only standard transport wrappers at this lifecycle boundary, without trusting diagnostics. */
    @SuppressWarnings("removal")
    private static void rethrowLifecycleFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        if (failure == null) return;
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var cause = failure;
        while (cause != null && visited.add(cause)) {
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath fatal) throw fatal;
            if (!(cause instanceof CompletionException
                    || cause instanceof ExecutionException
                    || cause instanceof InvocationTargetException
                    || cause instanceof UndeclaredThrowableException)) return;
            try {
                cause = cause.getCause();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable unreadableDiagnostic) {
                return;
            }
        }
    }

    private static SafeCloseable restoreMdcOnClose() {
        var previous = MDC.getCopyOfContextMap();
        return () -> {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        };
    }

    private static String handleLargePayload(ExecutionManager executionManager, String outputPayload) {
        // Check if the serialized payload exceeds Lambda response size limit
        var payloadSize = outputPayload != null ? outputPayload.getBytes(StandardCharsets.UTF_8).length : 0;

        if (payloadSize > LAMBDA_RESPONSE_SIZE_LIMIT) {
            logger.debug(
                    "Response size ({} bytes) exceeds Lambda limit ({} bytes). Checkpointing result.",
                    payloadSize,
                    LAMBDA_RESPONSE_SIZE_LIMIT);

            // Checkpoint the large result and wait for it to complete
            executionManager
                    .sendOperationUpdate(OperationUpdate.builder()
                            .type(OperationType.EXECUTION)
                            .id(executionManager.getExecutionOperation().id())
                            .action(OperationAction.SUCCEED)
                            .payload(outputPayload)
                            .build())
                    .join();

            // Return empty result, we checkpointed the data manually
            logger.debug("Execution completed (large response checkpointed)");
            return "";
        }

        // If response size is acceptable, return the result directly
        return outputPayload;
    }

    private static ErrorObject buildErrorObject(Throwable e, SerDes serDes) {
        // exceptions thrown from operations, e.g. Step
        if (e instanceof DurableOperationException durableOperationException) {
            return durableOperationException.getErrorObject();
        }
        if (e instanceof UnrecoverableDurableExecutionException unrecoverableDurableExecutionException) {
            return unrecoverableDurableExecutionException.getErrorObject();
        }
        // exceptions thrown from non-operation code
        return ExceptionHelper.buildErrorObject(e, serDes);
    }

    private static <I> I extractUserInput(Operation executionOp, SerDes serDes, TypeToken<I> inputType) {
        if (executionOp.executionDetails() == null) {
            throw new IllegalDurableOperationException("EXECUTION operation missing executionDetails");
        }

        var inputPayload = executionOp.executionDetails().inputPayload();
        return serDes.deserialize(inputPayload, inputType);
    }

    /**
     * Wraps a user handler in a RequestHandler that can be used by the Lambda runtime.
     *
     * @param inputType the type token for the input
     * @param handler the handler function
     * @param config the durable config
     * @return a request handler that executes the durable function
     * @param <I> the type of the input
     * @param <O> the type of the output
     */
    public static <I, O> RequestHandler<DurableExecutionInput, DurableExecutionOutput> wrap(
            TypeToken<I> inputType, BiFunction<I, DurableContext, O> handler, DurableConfig config) {
        return (input, context) -> execute(input, context, inputType, handler, config);
    }
}
