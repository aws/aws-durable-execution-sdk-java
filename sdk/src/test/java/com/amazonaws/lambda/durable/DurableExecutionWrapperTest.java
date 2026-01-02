// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;

class DurableExecutionWrapperTest {

    static class TestInput {
        public String value;

        public TestInput() {}

        public TestInput(String value) {
            this.value = value;
        }
    }

    static class TestOutput {
        public String result;

        public TestOutput() {}

        public TestOutput(String result) {
            this.result = result;
        }
    }

    private DurableExecutionClient mockClient() {
        return TestUtils.createMockClient();
    }

    @Test
    void testWrapperPattern() {
        RequestHandler<DurableExecutionInput, DurableExecutionOutput> handler = DurableExecutor.wrap(
                TestInput.class,
                (input, context) -> {
                    var result = context.step("process", String.class, () -> "Wrapped: " + input.value);
                    return new TestOutput(result);
                },
                mockClient());

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
                new DurableExecutionInput.InitialExecutionState(of(executionOp), null));

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
        RequestHandler<DurableExecutionInput, DurableExecutionOutput> handler =
                DurableExecutor.wrap(TestInput.class, DurableExecutionWrapperTest::handleRequest, mockClient());

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
                new DurableExecutionInput.InitialExecutionState(of(executionOp), null));

        var output = handler.handleRequest(input, null);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        var result = serDes.deserialize(output.result(), TestOutput.class);
        assertEquals("Method: method-ref", result.result);
    }

    private static TestOutput handleRequest(TestInput input, DurableContext context) {
        var result = context.step("process", String.class, () -> "Method: " + input.value);
        return new TestOutput(result);
    }
}
