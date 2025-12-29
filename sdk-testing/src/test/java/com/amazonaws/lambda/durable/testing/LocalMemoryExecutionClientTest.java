// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;

class LocalMemoryExecutionClientTest {

    @Test
    void testCheckpoint() {
        var storage = new LocalMemoryExecutionClient();

        var update = OperationUpdate.builder()
                .id("1")
                .type(OperationType.STEP)
                .action(OperationAction.SUCCEED)
                .payload("result")
                .build();
        var response = storage.checkpoint("arn", "token", List.of(update));

        assertNotNull(response.checkpointToken());
        assertEquals(1, response.newExecutionState().operations().size());

        var op = response.newExecutionState().operations().get(0);
        assertEquals("1", op.id());
        assertEquals(OperationType.STEP, op.type());
        assertEquals(OperationStatus.SUCCEEDED, op.status());
        assertEquals("result", op.stepDetails().result());
    }

    @Test
    void testActionToStatusMapping() {
        var storage = new LocalMemoryExecutionClient();

        storage.checkpoint(
                "arn",
                "token",
                List.of(OperationUpdate.builder()
                        .id("1")
                        .type(OperationType.STEP)
                        .action(OperationAction.START)
                        .build()));
        storage.checkpoint(
                "arn",
                "token",
                List.of(OperationUpdate.builder()
                        .id("2")
                        .type(OperationType.STEP)
                        .action(OperationAction.SUCCEED)
                        .payload("ok")
                        .build()));
        storage.checkpoint(
                "arn",
                "token",
                List.of(OperationUpdate.builder()
                        .id("3")
                        .type(OperationType.STEP)
                        .action(OperationAction.FAIL)
                        .error(ErrorObject.builder()
                                .errorType("TestError")
                                .errorMessage("error")
                                .build())
                        .build()));

        var state = storage.getExecutionState("arn", null);

        assertEquals(OperationStatus.STARTED, state.operations().get(0).status());
        assertEquals(OperationStatus.SUCCEEDED, state.operations().get(1).status());
        assertEquals(OperationStatus.FAILED, state.operations().get(2).status());
    }
}
