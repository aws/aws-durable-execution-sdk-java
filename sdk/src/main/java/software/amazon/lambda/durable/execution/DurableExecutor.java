// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public static <I, O> DurableExecutionOutput execute(
            DurableExecutionInput input,
            Context lambdaContext,
            TypeToken<I> inputType,
            BiFunction<I, DurableContext, O> handler,
            DurableConfig config) {
        var pluginRunner = config.getPluginRunner();
        try (var executionManager = new ExecutionManager(input, config, lambdaContext)) {
            var isFirstInvocation = !executionManager.isReplaying();
            var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
            var executionArn = input.durableExecutionArn();

            executionManager.registerActiveThread(null);
            // Captured for onInvocationEnd, which runs outside the handler thread below.
            var pluginExecutionInput = new AtomicReference<>();
            var handlerFuture = CompletableFuture.supplyAsync(
                    () -> {
                        executionManager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));

                        // Deserialize once and share the value with the plugin hooks and the handler below. A second
                        // deserialization would double the cost, hand plugins a different object than the handler, and
                        // re-run any side effects in a stateful custom SerDes. A failure is captured rather than thrown
                        // so onInvocationStart still fires before it surfaces, keeping the start/end hooks paired.
                        // SerDes is a public extension point whose deserialize declares no checked exceptions, so an
                        // implementation may sneaky-throw one; capture every Throwable and rethrow it unchanged.
                        I userInput = null;
                        Throwable inputFailure = null;
                        try {
                            userInput = extractUserInput(
                                    executionManager.getExecutionOperation(), config.getSerDes(), inputType);
                        } catch (Throwable t) {
                            inputFailure = t;
                        }
                        pluginExecutionInput.set(userInput);

                        // onInvocationStart runs on the user thread so plugins can
                        // inject ThreadLocal objects, update MDC, etc.
                        // executionStartTime comes from the initial EXECUTION operation in the first backend event.
                        if (!pluginRunner.isEmpty()) {
                            pluginRunner.onInvocationStart(new InvocationInfo(
                                    requestId,
                                    executionArn,
                                    isFirstInvocation,
                                    executionManager.getExecutionOperation().startTimestamp(),
                                    userInput,
                                    PluginInfoConverter.toOperationItemMap(
                                            executionManager.getOperationsSnapshot(),
                                            executionManager.getInitialOperationIds()),
                                    PluginInfoConverter.toOperationItemMap(
                                            executionManager.getUpdatedOperationsSnapshot(),
                                            executionManager.getInitialOperationIds())));
                        }
                        if (inputFailure != null) {
                            ExceptionHelper.sneakyThrow(inputFailure);
                        }

                        var context = DurableContextImpl.createRootContext(executionManager, config, lambdaContext);
                        DurableContextImpl.setCurrentContext(context);
                        // use a try-with-resources to clear logger properties
                        try (var ignored = DurableLogger.attachContext()) {
                            return handler.apply(userInput, context);
                        }
                    },
                    config.getExecutorService()); // Get executor from config for running user code

            // Execute the handlerFuture in ExecutionManager. If it completes successfully, the output of user function
            // will be returned. Otherwise, it will complete exceptionally with a SuspendExecutionException or a
            // failure.
            try {
                return executionManager
                        .runUntilCompleteOrSuspend(handlerFuture)
                        .handle((result, ex) -> {
                            if (ex != null) {
                                // an exception thrown from handlerFuture or suspension/termination occurred
                                Throwable cause = ExceptionHelper.unwrapCompletableFuture(ex);

                                // A checkpoint response can omit the checkpoint token on a thread other than the one
                                // that produced `cause`, e.g. an unfinished map/parallel branch's checkpoint that the
                                // handler never awaited. This consult must win over both the retryable-rethrow and
                                // FAILED exits below: resending the omitted token would fail one call later, so this
                                // invocation must return PENDING and must not issue further checkpoints. A plain
                                // SuspendExecutionException is ordinary control flow, not an execution error, so
                                // plugins only see `cause` when it is a genuine error the latch happened to
                                // accompany.
                                if (cause instanceof SuspendExecutionException
                                        || executionManager.isCheckpointTokenRevoked()) {
                                    var pluginError = cause instanceof SuspendExecutionException ? null : cause;
                                    fireOnInvocationEnd(
                                            pluginRunner,
                                            executionManager,
                                            requestId,
                                            executionArn,
                                            isFirstInvocation,
                                            InvocationStatus.PENDING,
                                            pluginError,
                                            pluginExecutionInput.get(),
                                            null);
                                    return DurableExecutionOutput.pending();
                                }

                                // let the backend retry the invocation if the exception is retryable
                                if (cause
                                                instanceof
                                                UnrecoverableDurableExecutionException
                                                        unrecoverableDurableExecutionException
                                        && unrecoverableDurableExecutionException.isRetryable()) {
                                    fireOnInvocationEnd(
                                            pluginRunner,
                                            executionManager,
                                            requestId,
                                            executionArn,
                                            isFirstInvocation,
                                            InvocationStatus.RETRYING,
                                            cause,
                                            pluginExecutionInput.get(),
                                            null);
                                    throw unrecoverableDurableExecutionException;
                                }

                                // fail the execution otherwise
                                logger.debug("Execution failed: {}", cause.getMessage());
                                fireOnInvocationEnd(
                                        pluginRunner,
                                        executionManager,
                                        requestId,
                                        executionArn,
                                        isFirstInvocation,
                                        InvocationStatus.FAILED,
                                        cause,
                                        pluginExecutionInput.get(),
                                        null);
                                return DurableExecutionOutput.failure(buildErrorObject(cause, config.getSerDes()));
                            }
                            // user handler complete successfully
                            logger.debug("Execution completed");

                            // A checkpoint response can omit the checkpoint token on a thread other than the
                            // one that produced `result`, e.g. an unfinished map/parallel branch's checkpoint
                            // the handler never awaited. This consult must win over the ordinary success path
                            // below: the service will not record this result, so this invocation must return
                            // PENDING rather than serialize and report a result the service never sees.
                            if (executionManager.isCheckpointTokenRevoked()) {
                                fireOnInvocationEnd(
                                        pluginRunner,
                                        executionManager,
                                        requestId,
                                        executionArn,
                                        isFirstInvocation,
                                        InvocationStatus.PENDING,
                                        null,
                                        pluginExecutionInput.get(),
                                        null);
                                return DurableExecutionOutput.pending();
                            }
                            var outputPayload = config.getSerDes().serialize(result);
                            try {
                                var output = DurableExecutionOutput.success(
                                        handleLargePayload(executionManager, outputPayload));
                                fireOnInvocationEnd(
                                        pluginRunner,
                                        executionManager,
                                        requestId,
                                        executionArn,
                                        isFirstInvocation,
                                        InvocationStatus.SUCCEEDED,
                                        null,
                                        pluginExecutionInput.get(),
                                        result);
                                return output;
                            } catch (SuspendExecutionException suspendExecutionException) {
                                // The oversized-result checkpoint (handleLargePayload) never reached the service, or
                                // this invocation's checkpoint response already omitted the token: either way the
                                // execution's terminal update was not recorded, so this is not a success. Report
                                // PENDING, the same outcome the ex != null branch above reports for an unawaited
                                // checkpoint. A plain SuspendExecutionException here is ordinary control flow, not an
                                // execution error, so plugins see no error.
                                fireOnInvocationEnd(
                                        pluginRunner,
                                        executionManager,
                                        requestId,
                                        executionArn,
                                        isFirstInvocation,
                                        InvocationStatus.PENDING,
                                        null,
                                        pluginExecutionInput.get(),
                                        null);
                                return DurableExecutionOutput.pending();
                            }
                        })
                        .join();
            } catch (CompletionException e) {
                // unwrap the CompletionException and rethrow the wrapped exception
                ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(e));
                return null;
            }
        }
    }

    private static void fireOnInvocationEnd(
            PluginRunner pluginRunner,
            ExecutionManager executionManager,
            String requestId,
            String executionArn,
            boolean isFirstInvocation,
            InvocationStatus status,
            Throwable error,
            Object executionInput,
            Object executionResult) {
        if (pluginRunner.isEmpty()) {
            return;
        }
        pluginRunner.onInvocationEnd(new InvocationEndInfo(
                requestId,
                executionArn,
                isFirstInvocation,
                executionManager.getExecutionOperation().startTimestamp(),
                PluginInfoConverter.toOperationItemMap(
                        executionManager.getOperationsSnapshot(), executionManager.getInitialOperationIds()),
                status,
                error,
                executionInput,
                executionResult));
    }

    private static String handleLargePayload(ExecutionManager executionManager, String outputPayload) {
        // Check if the serialized payload exceeds Lambda response size limit
        var payloadSize = outputPayload != null ? outputPayload.getBytes(StandardCharsets.UTF_8).length : 0;

        if (payloadSize > LAMBDA_RESPONSE_SIZE_LIMIT) {
            logger.debug(
                    "Response size ({} bytes) exceeds Lambda limit ({} bytes). Checkpointing result.",
                    payloadSize,
                    LAMBDA_RESPONSE_SIZE_LIMIT);

            // Checkpoint the large result and wait for it to complete. This must report whether the service
            // accepted this EXECUTION update rather than assume it from the future completing: CheckpointManager
            // throws SuspendExecutionException, instead of completing normally, whenever the update is abandoned
            // (the token was already revoked, or execution processing had already stopped), so join() surfaces that
            // as a CompletionException here rather than blocking. Unwrap and rethrow it unchanged so the caller can
            // pattern-match a plain SuspendExecutionException the same way the rest of this class already does.
            try {
                executionManager
                        .sendOperationUpdate(OperationUpdate.builder()
                                .type(OperationType.EXECUTION)
                                .id(executionManager.getExecutionOperation().id())
                                .action(OperationAction.SUCCEED)
                                .payload(outputPayload)
                                .build())
                        .join();
            } catch (CompletionException e) {
                var cause = ExceptionHelper.unwrapCompletableFuture(e);
                if (cause instanceof SuspendExecutionException suspendExecutionException) {
                    throw suspendExecutionException;
                }
                throw e;
            }

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
