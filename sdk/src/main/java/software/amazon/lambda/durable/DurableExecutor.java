// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

public class DurableExecutor {
    private static final Logger logger = LoggerFactory.getLogger(DurableExecutor.class);

    // Lambda response size limit is 6MB minus small epsilon for envelope
    private static final int LAMBDA_RESPONSE_SIZE_LIMIT = 6 * 1024 * 1024 - 50;

    public static <I, O> DurableExecutionOutput execute(
            DurableExecutionInput input,
            Context lambdaContext,
            Class<I> inputType,
            BiFunction<I, DurableContext, O> handler,
            DurableConfig config) {
        var executionManager = new ExecutionManager(
                input.durableExecutionArn(), input.checkpointToken(), input.initialExecutionState(), config);

        var handlerFuture = CompletableFuture.supplyAsync(
                () -> {
                    var userInput =
                            extractUserInput(executionManager.getExecutionOperation(), config.getSerDes(), inputType);
                    // Create context in the executor thread so it detects the correct thread name
                    var context = DurableContext.createRootContext(executionManager, config, lambdaContext);
                    return handler.apply(userInput, context);
                },
                config.getExecutorService()); // Get executor from config for running user code

        // Execute the handlerFuture in ExecutionManager. If it completes successfully, the output of user function
        // will be returned. Otherwise, it will complete exceptionally with a SuspendExecutionException or a failure.
        return executionManager
                .runUntilCompleteOrSuspend(handlerFuture)
                .handle((result, ex) -> {
                    if (ex != null) {
                        // an exception thrown from handlerFuture or suspension/termination occurred
                        Throwable cause = ExceptionHelper.unwrapCompletableFuture(ex);
                        if (cause instanceof SuspendExecutionException) {
                            return DurableExecutionOutput.pending();
                        }

                        logger.debug("Execution failed: {}", cause.getMessage());
                        return DurableExecutionOutput.failure(buildErrorObject(cause, config.getSerDes()));
                    }
                    // user handler complete successfully
                    var outputPayload = config.getSerDes().serialize(result);

                    logger.debug("Execution completed");
                    return DurableExecutionOutput.success(handleLargePayload(executionManager, outputPayload));
                })
                .whenComplete((v, ex) -> {
                    // We shutdown the execution to make sure remaining checkpoint calls in the queue are drained
                    // We DO NOT shutdown the executor since it should stay warm for re-invokes against a warm Lambda
                    // runtime.
                    // For example, a re-invoke after a wait should re-use the same executor instance from
                    // DurableConfig.
                    // userExecutor.shutdown();
                    executionManager.shutdown();
                })
                .join();
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
        if (e instanceof DurableOperationException) {
            return ((DurableOperationException) e).getErrorObject();
        }
        if (e instanceof UnrecoverableDurableExecutionException) {
            return ((UnrecoverableDurableExecutionException) e).getErrorObject();
        }
        // exceptions thrown from non-operation code
        return ExceptionHelper.buildErrorObject(e, serDes);
    }

    private static <I> I extractUserInput(Operation executionOp, SerDes serDes, Class<I> inputType) {
        if (executionOp.executionDetails() == null) {
            throw new IllegalDurableOperationException("EXECUTION operation missing executionDetails");
        }

        var inputPayload = executionOp.executionDetails().inputPayload();
        return serDes.deserialize(inputPayload, TypeToken.get(inputType));
    }

    public static <I, O> RequestHandler<DurableExecutionInput, DurableExecutionOutput> wrap(
            Class<I> inputType, BiFunction<I, DurableContext, O> handler, DurableConfig config) {
        return (input, context) -> execute(input, context, inputType, handler, config);
    }
}
