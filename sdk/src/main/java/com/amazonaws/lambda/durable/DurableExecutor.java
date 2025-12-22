package com.amazonaws.lambda.durable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.client.LambdaDurableFunctionsClient;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;

public class DurableExecutor {
    private static final Logger logger = LoggerFactory.getLogger(DurableExecutor.class);

    public static <I, O> DurableExecutionOutput execute(
            DurableExecutionInput input,
            Context lambdaContext,
            Class<I> inputType,
            BiFunction<I, DurableContext, O> handler) {
        // TODO: Allow passing client by user
        logger.debug("Initialize SDK client");
        var client = new LambdaDurableFunctionsClient();
        logger.debug("Done initializing SDK client");
        return execute(input, lambdaContext, inputType, handler, client);
    }

    public static <I, O> DurableExecutionOutput execute(
            DurableExecutionInput input,
            Context lambdaContext,
            Class<I> inputType,
            BiFunction<I, DurableContext, O> handler,
            DurableExecutionClient client) {
        logger.debug("DurableExecution.execute() called");
        logger.debug("DurableExecutionArn: {}", input.durableExecutionArn());
        logger.debug("CheckpointToken: {}", input.checkpointToken());
        logger.debug("Initial operations count: {}",
                input.initialExecutionState() != null && input.initialExecutionState().operations() != null
                        ? input.initialExecutionState().operations().size()
                        : 0);

        // Validate initial operation is an EXECUTION operation
        // TODO: Double check if very large inputs (close to 6MB) have a null
        // initialExecutionState.
        // Potentially, we need to call the backend to fetch it. Give it to the manager,
        // have it load it from the
        // if null.
        if (input.initialExecutionState() == null || input.initialExecutionState().operations() == null
                || input.initialExecutionState().operations().isEmpty()
                || input.initialExecutionState().operations().get(0).type() != OperationType.EXECUTION) {
            throw new IllegalStateException("First operation must be EXECUTION");
        }

        // Create single executor for both handler and steps
        // TODO: Allow passing executor by user through DurableHandler
        var executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("durable-exec-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        // TODO: Should we pass the whole input instead?
        var executionManager = new ExecutionManager(
                input.durableExecutionArn(),
                input.checkpointToken(),
                input.initialExecutionState(),
                client,
                executor);

        var executionOp = executionManager.getExecutionOperation();
        logger.debug("EXECUTION operation found: {}", executionOp.id());
        var serDes = new JacksonSerDes();
        var userInput = extractUserInput(executionOp, serDes, inputType);

        // Create context
        var context = new DurableContext(executionManager, serDes, lambdaContext);

        try {
            var handlerFuture = CompletableFuture.supplyAsync(() -> handler.apply(userInput, context), executor);

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
                logger.debug("Execution suspended.");
                return DurableExecutionOutput.pending();
            }

            if (handlerFuture.isCompletedExceptionally()) {
                try {
                    handlerFuture.join(); // Will throw the exception
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return DurableExecutionOutput.failure(cause);
                }
            }

            var result = handlerFuture.get();

            // TODO: Understand if we need to checkpoint the EXECUTION operation here.

            return DurableExecutionOutput.success(serDes.serialize(result));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return DurableExecutionOutput.failure(cause);
        } finally {
            executionManager.shutdown();
            executor.shutdown();
        }
    }

    private static <I> I extractUserInput(
            Operation executionOp,
            SerDes serDes,
            Class<I> inputType) {

        if (executionOp.executionDetails() == null) {
            throw new IllegalStateException("EXECUTION operation missing executionDetails");
        }

        var inputPayload = executionOp.executionDetails().inputPayload();
        return serDes.deserialize(inputPayload, inputType);
    }

    public static <I, O> RequestHandler<DurableExecutionInput, DurableExecutionOutput> wrap(
            Class<I> inputType,
            BiFunction<I, DurableContext, O> handler) {
        return (input, context) -> execute(input, context, inputType, handler);
    }

    public static <I, O> RequestHandler<DurableExecutionInput, DurableExecutionOutput> wrap(
            Class<I> inputType,
            BiFunction<I, DurableContext, O> handler,
            DurableExecutionClient client) {
        return (input, context) -> execute(input, context, inputType, handler, client);
    }
}
