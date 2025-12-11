package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.checkpoint.CheckpointManager;
import com.amazonaws.lambda.durable.checkpoint.SuspendExecutionException;
import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.client.LambdaDurableFunctionsClient;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.model.ErrorObject;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.checkpoint.ExecutionState;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

public class DurableExecution {
    private static final Logger logger = LoggerFactory.getLogger(DurableExecution.class);
    
    public static <I, O> DurableExecutionOutput execute(
            DurableExecutionInput input,
            Context lambdaContext,
            Class<I> inputType,
            BiFunction<I, DurableContext, O> handler) {

        //Todo: Allow passing client by user
        logger.debug("Initialize SDK client");
        var client = new LambdaDurableFunctionsClient(null);
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

        var operations = loadAllOperations(input, client);
        logger.debug("Total operations loaded: {}", operations.size());
        
        // Validate and extract EXECUTION operation
        if (operations.isEmpty() || operations.get(0).type() != OperationType.EXECUTION) {
            throw new IllegalStateException("First operation must be EXECUTION");
        }
        
        var executionOp = operations.get(0);
        logger.debug("EXECUTION operation found: {}", executionOp.id());
        var serDes = new JacksonSerDes();
        var userInput = extractUserInput(executionOp, serDes, inputType);
        
        // Create state and checkpoint manager
        var state = new ExecutionState(
            input.durableExecutionArn(),
            input.checkpointToken(),
            operations
        );
        logger.debug("--- State initialized ---");
        var executor = Executors.newSingleThreadExecutor();
        var checkpointManager = new CheckpointManager(state, client, executor);
        var context = new DurableContext(checkpointManager, serDes, lambdaContext);
        logger.debug("--- Context initialized ---");
        try {
            var result = handler.apply(userInput, context);
            logger.debug("--- Handler returned ---");
            return DurableExecutionOutput.success(serDes.serialize(result));
            
        } catch (SuspendExecutionException e) {
            return DurableExecutionOutput.pending();
        } catch (Exception e) {
            return DurableExecutionOutput.failure(ErrorObject.fromException(e));
        } finally {
            checkpointManager.shutdown();
            executor.shutdown();
        }
    }
    
    private static ArrayList<Operation> loadAllOperations(DurableExecutionInput input, DurableExecutionClient client) {
        if (input.initialExecutionState() == null || input.initialExecutionState().operations() == null) {
            return new ArrayList<>();
        }
        
        var operations = new ArrayList<>(input.initialExecutionState().operations());
        var nextMarker = input.initialExecutionState().nextMarker();
        
        while (nextMarker != null) {
            var response = client.getExecutionState(input.durableExecutionArn(), nextMarker);
            operations.addAll(response.operations());
            nextMarker = response.nextMarker();
        }
        return operations;
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
