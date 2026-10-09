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

            try {
                // User work may be queued on a bounded executor. Drain on the invocation thread so completing the
                // handler cannot occupy the executor thread that the queued work needs.
                var handlerResult = executionManager
                        .runUntilCompleteOrSuspend(handlerFuture)
                        .handle(HandlerResult::new)
                        .join();
                executionManager.drainOperations();

                var outcome = finishInvocation(handlerResult, executionManager, config);
                fireOnInvocationEnd(
                        pluginRunner,
                        executionManager,
                        requestId,
                        executionArn,
                        isFirstInvocation,
                        outcome.status(),
                        outcome.error(),
                        pluginExecutionInput.get(),
                        outcome.status() == InvocationStatus.SUCCEEDED ? handlerResult.result() : null);
                if (outcome.status() == InvocationStatus.RETRYING) {
                    ExceptionHelper.sneakyThrow(outcome.error());
                }
                return outcome.output();
            } catch (CompletionException e) {
                // unwrap the CompletionException and rethrow the wrapped exception
                ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(e));
                return null;
            }
        }
    }

    private record HandlerResult(Object result, Throwable error) {}

    private record InvocationOutcome(DurableExecutionOutput output, InvocationStatus status, Throwable error) {}

    private static InvocationOutcome finishInvocation(
            HandlerResult handlerResult, ExecutionManager executionManager, DurableConfig config) {
        var cause = ExceptionHelper.unwrapCompletableFuture(handlerResult.error());
        if (executionManager.isCheckpointTokenRevoked() || cause instanceof SuspendExecutionException) {
            return new InvocationOutcome(DurableExecutionOutput.pending(), InvocationStatus.PENDING, null);
        }
        if (cause instanceof UnrecoverableDurableExecutionException error && error.isRetryable()) {
            return new InvocationOutcome(null, InvocationStatus.RETRYING, cause);
        }
        if (cause != null) {
            logger.debug("Execution failed: {}", cause.getMessage());
            return new InvocationOutcome(
                    DurableExecutionOutput.failure(buildErrorObject(cause, config.getSerDes())),
                    InvocationStatus.FAILED,
                    cause);
        }

        // Polling has stopped and submitted checkpoints have settled, so only the terminal result can use the token.
        var outputPayload = config.getSerDes().serialize(handlerResult.result());
        try {
            logger.debug("Execution completed");
            return new InvocationOutcome(
                    DurableExecutionOutput.success(handleLargePayload(executionManager, outputPayload)),
                    InvocationStatus.SUCCEEDED,
                    null);
        } catch (CompletionException e) {
            if (ExceptionHelper.unwrapCompletableFuture(e) instanceof SuspendExecutionException) {
                return new InvocationOutcome(DurableExecutionOutput.pending(), InvocationStatus.PENDING, null);
            }
            throw e;
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
