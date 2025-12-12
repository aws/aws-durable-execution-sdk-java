package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.testing.LocalMemoryExecutionClient;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;

import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.*;

class DurableExecutionWrapperTest {
    
    static class TestInput {
        public String value;
        
        public TestInput() {}
        public TestInput(String value) { this.value = value; }
    }
    
    static class TestOutput {
        public String result;
        
        public TestOutput() {}
        public TestOutput(String result) { this.result = result; }
    }
    
    @Test
    void testWrapperPattern() {
        var client = new LocalMemoryExecutionClient();
        
        // Create handler using wrapper
        RequestHandler<DurableExecutionInput, DurableExecutionOutput> handler = 
            DurableExecution.wrap(TestInput.class, (input, context) -> {
                var result = context.step("process", String.class, () -> 
                    "Wrapped: " + input.value
                );
                return new TestOutput(result);
            }, client);
        
        var serDes = new JacksonSerDes();
        
        // Create input with EXECUTION operation
        var executionOp = Operation.builder()
            .id("0")
            .type(OperationType.EXECUTION)
            .status(OperationStatus.STARTED)
            .executionDetails(ExecutionDetails.builder()
                .inputPayload(serDes.serialize(new TestInput("test")))
                .build())
            .build();
        
        var input = new DurableExecutionInput(
            "arn:aws:lambda:us-east-1:123456789012:function:test",
            "token-1",
            new DurableExecutionInput.InitialExecutionState(of(executionOp), null)
        );
        
        // Execute
        var output = handler.handleRequest(input, null);
        
        // Verify
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());
        
        var result = serDes.deserialize(output.result(), TestOutput.class);
        assertEquals("Wrapped: test", result.result);
    }
    
    @Test
    void testWrapperWithMethodReference() {
        var client = new LocalMemoryExecutionClient();
        
        // Create handler using method reference
        RequestHandler<DurableExecutionInput, DurableExecutionOutput> handler = 
            DurableExecution.wrap(TestInput.class, DurableExecutionWrapperTest::handleRequest, client);
        
        var serDes = new JacksonSerDes();
        
        var executionOp = Operation.builder()
            .id("0")
            .type(OperationType.EXECUTION)
            .status(OperationStatus.STARTED)
            .executionDetails(ExecutionDetails.builder()
                .inputPayload(serDes.serialize(new TestInput("method-ref")))
                .build())
            .build();
        
        var input = new DurableExecutionInput(
            "arn:aws:lambda:us-east-1:123456789012:function:test",
            "token-1",
            new DurableExecutionInput.InitialExecutionState(of(executionOp), null)
        );
        
        var output = handler.handleRequest(input, null);
        
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        var result = serDes.deserialize(output.result(), TestOutput.class);
        assertEquals("Method: method-ref", result.result);
    }
    
    private static TestOutput handleRequest(TestInput input, DurableContext context) {
        var result = context.step("process", String.class, () -> 
            "Method: " + input.value
        );
        return new TestOutput(result);
    }
}
