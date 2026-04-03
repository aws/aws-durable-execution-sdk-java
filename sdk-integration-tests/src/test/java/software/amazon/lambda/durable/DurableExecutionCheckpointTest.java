// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

/** Integration tests that verify checkpoint behavior using LocalMemoryExecutionClient */
class DurableExecutionCheckpointTest {
    private static final String EXECUTION_NAME = "test-execution";
    private static final String EXECUTION_OP_ID = "01234567-0123-0123-0123-012345678901";
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/"
            + EXECUTION_NAME + "/" + EXECUTION_OP_ID;

    private DurableConfig configWithMockClient(LocalMemoryExecutionClient client) {
        return DurableConfig.builder().withDurableExecutionClient(client).build();
    }

    @Test
    void testLargePayloadCheckpointing() {
        var client = new LocalMemoryExecutionClient();
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var largeString = "x".repeat(7 * 1024 * 1024); // 7MB string

        var output = DurableExecutor.execute(
                input,
                null,
                TypeToken.get(String.class),
                (userInput, ctx) -> largeString,
                configWithMockClient(client));

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("", output.result());

        var updates = client.getOperationUpdates();
        assertFalse(updates.isEmpty());
        var lastUpdate = updates.get(updates.size() - 1);
        assertEquals(OperationType.EXECUTION, lastUpdate.type());
        assertEquals(OperationAction.SUCCEED, lastUpdate.action());
        assertNotNull(lastUpdate.payload());
        assertTrue(lastUpdate.payload().length() > 6 * 1024 * 1024);
    }

    @Test
    void testSmallPayloadNoExtraCheckpoint() {
        var client = new LocalMemoryExecutionClient();
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var smallResult = "Small result";

        var output = DurableExecutor.execute(
                input,
                null,
                TypeToken.get(String.class),
                (userInput, ctx) -> smallResult,
                configWithMockClient(client));

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());
        assertTrue(output.result().contains(smallResult));

        var updates = client.getOperationUpdates();
        var executionUpdates = updates.stream()
                .filter(u -> u.type() == OperationType.EXECUTION)
                .toList();
        assertTrue(executionUpdates.isEmpty());
    }
}
