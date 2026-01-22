// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

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
        logger.debug("DurableExecution.execute() called");
        logger.debug("DurableExecutionArn: {}", input.durableExecutionArn());
        logger.debug(
                "Initial operations count: {}",
                input.initialExecutionState() != null
                                && input.initialExecutionState().operations() != null
                        ? input.initialExecutionState().operations().size()
                        : 0);

        // Validate initial operation is an EXECUTION operation
        // TODO: Double check if very large inputs (close to 6MB) have a null
        // initialExecutionState.
        // Potentially, we need to call the backend to fetch it. Give it to the manager,
        // have it load it from the
        // if null.
        if (input.initialExecutionState() == null
                || input.initialExecutionState().operations() == null
                || input.initialExecutionState().operations().isEmpty()
                || input.initialExecutionState().operations().get(0).type() != OperationType.EXECUTION) {
            throw new IllegalStateException("First operation must be EXECUTION");
        }

        // Get executor from config (always non-null)
        var executor = config.getExecutorService();

        // TODO: Should we pass the whole input instead?
        var executionManager = new ExecutionManager(
                input.durableExecutionArn(),
                input.checkpointToken(),
                input.initialExecutionState(),
                config.getDurableExecutionClient(),
                executor);

        var executionOp = executionManager.getExecutionOperation();
        logger.debug("EXECUTION operation found: {}", executionOp.id());

        // Use SerDes from config (defaults to JacksonSerDes)
        var serDes = config.getSerDes();
        var userInput = extractUserInput(executionOp, serDes, inputType);

        try {
            var handlerFuture = CompletableFuture.supplyAsync(
                    () -> {
                        // Create context in the executor thread so it detects the correct thread name
                        var context =
                                new DurableContext(executionManager, serDes, lambdaContext, config.getLoggerConfig());
                        return handler.apply(userInput, context);
                    },
                    executor);

            // Get suspend future from ExecutionManager. If this future completes, it
            // indicates
            // that no threads are active and we can safely suspend. This is useful for
            // async scenarios where multiple operations are scheduled concurrently and
            // awaited
            // at a later point.
            var suspendFuture = executionManager.getSuspendExecutionFuture();

            // Wait for either handler to complete or suspension to occur
            CompletableFuture.anyOf(handlerFuture, suspendFuture).join();

            if (suspendFuture.isDone()) {
                logger.debug("Execution suspended");
                return DurableExecutionOutput.pending();
            }

            if (handlerFuture.isCompletedExceptionally()) {
                try {
                    handlerFuture.join(); // Will throw the exception
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    logger.debug("Execution failed: {}", cause.getMessage());
                    return DurableExecutionOutput.failure(cause);
                }
            }

            var result = handlerFuture.get();
            var outputPayload = serDes.serialize(result);

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
                                .id(executionOp.id())
                                .action(OperationAction.SUCCEED)
                                .payload(outputPayload)
                                .build())
                        .join();

                // Return empty result, we checkpointed the data manually
                logger.debug("Execution completed (large response checkpointed)");
                return DurableExecutionOutput.success("");
            }

            // If response size is acceptable, return the result directly
            logger.debug("Execution completed");
            return DurableExecutionOutput.success(outputPayload);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return DurableExecutionOutput.failure(cause);
        } finally {
            // We shutdown the execution to make sure remaining checkpoint calls in the queue are drained
            executionManager.shutdown();

            // We DO NOT shutdown the executor since it should stay warm for re-invokes against a warm Lambda runtime.
            // For example, a re-invoke after a wait should re-use the same executor instance from DurableConfig.
            // executor.shutdown();
        }
    }

    private static <I> I extractUserInput(Operation executionOp, SerDes serDes, Class<I> inputType) {

        if (executionOp.executionDetails() == null) {
            throw new IllegalStateException("EXECUTION operation missing executionDetails");
        }

        var inputPayload = executionOp.executionDetails().inputPayload();
        return serDes.deserialize(inputPayload, inputType);
    }

    public static <I, O> RequestHandler<DurableExecutionInput, DurableExecutionOutput> wrap(
            Class<I> inputType, BiFunction<I, DurableContext, O> handler, DurableConfig config) {
        return (input, context) -> execute(input, context, inputType, handler, config);
    }
}
