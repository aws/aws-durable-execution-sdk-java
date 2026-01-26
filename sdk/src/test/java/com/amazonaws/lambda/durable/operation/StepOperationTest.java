// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.OperationContext;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.logging.DurableLogger;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.Test;

class StepOperationTest {

    @Test
    void getThrowsIllegalStateExceptionWhenCalledFromStepContext() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("1-step", ThreadType.STEP));

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                new JacksonSerDes());

        var ex = assertThrows(IllegalStateException.class, operation::get);
        assertTrue(ex.getMessage().contains("Nested step calling is not supported"));
        assertTrue(ex.getMessage().contains("test-step"));
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister(); // Advance to phase 1 to skip blocking
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
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
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                new JacksonSerDes());

        var result = operation.get();
        assertEquals("cached-result", result);
    }
}
