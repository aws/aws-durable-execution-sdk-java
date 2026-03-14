// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static software.amazon.lambda.durable.TypeToken.get;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class DurableExecutionWrapperTest {
    private static final String EXECUTION_OP_ID = "f3d7b0c0-1234-5678-90ab-cdef12345678";
    private static final String EXECUTION_NAME = "exec-name";
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/"
            + EXECUTION_NAME + "/" + EXECUTION_OP_ID;

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
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient()).build();
        RequestHandler<DurableExecutionInput, DurableExecutionOutput> handler = DurableExecutor.wrap(
                get(TestInput.class),
                (input, context) -> {
                    var result = context.step("process", String.class, stepCtx -> "Wrapped: " + input.value);
                    return new TestOutput(result);
                },
                config);

        var serDes = new JacksonSerDes();

        // Create input with EXECUTION operation
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload(serDes.serialize(new TestInput("test")))
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token-1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        // Execute
        var output = handler.handleRequest(input, null);

        // Verify
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());

        var result = serDes.deserialize(output.result(), get(TestOutput.class));
        assertEquals("Wrapped: test", result.result);
    }

    @Test
    void testWrapperWithMethodReference() {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient()).build();
        RequestHandler<DurableExecutionInput, DurableExecutionOutput> handler =
                DurableExecutor.wrap(get(TestInput.class), DurableExecutionWrapperTest::handleRequest, config);

        var serDes = new JacksonSerDes();

        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload(serDes.serialize(new TestInput("method-ref")))
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token-1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var output = handler.handleRequest(input, null);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        var result = serDes.deserialize(output.result(), get(TestOutput.class));
        assertEquals("Method: method-ref", result.result);
    }

    private static TestOutput handleRequest(TestInput input, DurableContext context) {
        var result = context.step("process", String.class, stepCtx -> "Method: " + input.value);
        return new TestOutput(result);
    }
}
