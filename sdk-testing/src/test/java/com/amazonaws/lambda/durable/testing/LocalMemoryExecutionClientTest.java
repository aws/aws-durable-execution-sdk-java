// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

class LocalMemoryExecutionClientTest {

    @Test
    void testOperationLookup() {
        var client = new LocalMemoryExecutionClient();

        var update1 = OperationUpdate.builder()
                .id("1")
                .name("step-1")
                .type(OperationType.STEP)
                .action(OperationAction.SUCCEED)
                .payload("\"result1\"")
                .build();

        var update2 = OperationUpdate.builder()
                .id("2")
                .name("step-2")
                .type(OperationType.STEP)
                .action(OperationAction.SUCCEED)
                .payload("\"result2\"")
                .build();

        client.checkpoint("arn", "token", List.of(update1, update2));

        var op1 = client.getOperationByName("step-1");
        assertNotNull(op1);
        assertEquals("step-1", op1.name());

        var op2 = client.getOperationByName("step-2");
        assertNotNull(op2);
        assertEquals("step-2", op2.name());

        assertEquals(2, client.getAllOperations().size());
    }

    @Test
    void testResetCheckpointToStartedThrowsWhenOperationNotFound() {
        var client = new LocalMemoryExecutionClient();

        var exception =
                assertThrows(IllegalStateException.class, () -> client.resetCheckpointToStarted("nonexistent-step"));
        assertEquals("Operation not found: nonexistent-step", exception.getMessage());
    }

    @Test
    void testSimulateFireAndForgetCheckpointLossThrowsWhenOperationNotFound() {
        var client = new LocalMemoryExecutionClient();

        var exception = assertThrows(
                IllegalStateException.class, () -> client.simulateFireAndForgetCheckpointLoss("nonexistent-step"));
        assertEquals("Operation not found: nonexistent-step", exception.getMessage());
    }
}
