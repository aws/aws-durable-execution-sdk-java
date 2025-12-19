package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.*;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class LocalDurableTestRunner<I, O> {
    private final Class<I> inputType;
    private final BiFunction<I, DurableContext, O> handler;
    private final LocalMemoryExecutionClient storage;
    private final SerDes serDes;
    
    public LocalDurableTestRunner(Class<I> inputType, BiFunction<I, DurableContext, O> handler) {
        this.inputType = inputType;
        this.handler = handler;
        this.storage = new LocalMemoryExecutionClient();
        this.serDes = new JacksonSerDes();
    }
    
    public TestResult<O> run(I input) {
        var durableInput = createDurableInput(input);
        var output = DurableExecutor.execute(durableInput, mockLambdaContext(), inputType, handler, storage);
        
        return new TestResult<>(output, storage);
    }
    
    private DurableExecutionInput createDurableInput(I input) {
        var inputJson = serDes.serialize(input);
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder().inputPayload(inputJson).build())
                .build();
        
        // Simulate what Lambda actually does: load previous operations and include them in InitialExecutionState
        var existingOps = storage.getExecutionState("arn:aws:lambda:us-east-1:123456789012:function:test", null).operations();
        var allOps = new ArrayList<>(List.of(executionOp));
        allOps.addAll(existingOps);
        
        return new DurableExecutionInput(
            "arn:aws:lambda:us-east-1:123456789012:function:test",
            "test-token",
            new DurableExecutionInput.InitialExecutionState(allOps, null)
        );
    }
    
    private Context mockLambdaContext() {
        return null;  // Minimal - tests don't need real Lambda context
    }
}
