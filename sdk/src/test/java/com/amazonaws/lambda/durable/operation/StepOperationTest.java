// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.Test;

class StepOperationTest {

    @Test
    void getThrowsIllegalStateExceptionWhenCalledFromStepContext() throws Exception {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentThreadType()).thenReturn(ThreadType.STEP);

        var operation = new StepOperation<>(
                "1", "test-step", () -> "result", String.class, null, executionManager, new JacksonSerDes());

        var ex = assertThrows(IllegalStateException.class, operation::get);
        assertTrue(ex.getMessage().contains("Nested step calling is not supported"));
        assertTrue(ex.getMessage().contains("test-step"));
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() throws Exception {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister(); // Advance to phase 1 to skip blocking
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentThreadType()).thenReturn(ThreadType.CONTEXT);
        when(executionManager.getCurrentContextId()).thenReturn("handler");
        when(executionManager.getOperation("1"))
                .thenReturn(software.amazon.awssdk.services.lambda.model.Operation.builder()
                        .id("1")
                        .name("test-step")
                        .status(software.amazon.awssdk.services.lambda.model.OperationStatus.SUCCEEDED)
                        .stepDetails(software.amazon.awssdk.services.lambda.model.StepDetails.builder()
                                .result("\"cached-result\"")
                                .build())
                        .build());

        var operation = new StepOperation<>(
                "1", "test-step", () -> "result", String.class, null, executionManager, new JacksonSerDes());

        var result = operation.get();
        assertEquals("cached-result", result);
    }
}
