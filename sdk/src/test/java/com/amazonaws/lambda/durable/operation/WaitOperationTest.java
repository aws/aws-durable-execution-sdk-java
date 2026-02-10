// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.lambda.durable.exception.IllegalDurableOperationException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.OperationContext;
import com.amazonaws.lambda.durable.execution.ThreadType;
import java.time.Duration;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.WaitDetails;

class WaitOperationTest {

    @Test
    void getThrowsIllegalStateExceptionWhenCalledFromStepContext() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("1-step", ThreadType.STEP));

        var operation = new WaitOperation("2", "test-invoke", Duration.ofSeconds(10), executionManager);

        var ex = assertThrows(IllegalDurableOperationException.class, operation::get);
        assertEquals(
                "Nested WAIT operation is not supported on test-invoke from within a Step execution.", ex.getMessage());
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister(); // Advance to phase 1 to skip blocking
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(software.amazon.awssdk.services.lambda.model.Operation.builder()
                        .id("1")
                        .name("test-invoke")
                        .status(OperationStatus.SUCCEEDED)
                        .waitDetails(WaitDetails.builder().build())
                        .build());

        var operation = new WaitOperation("1", "test-invoke", Duration.ofSeconds(10), executionManager);

        var result = operation.get();
        assertNull(result);
    }

    @Test
    void getSucceededWhenStarted() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(0);
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(software.amazon.awssdk.services.lambda.model.Operation.builder()
                        .id("1")
                        .name("test-invoke")
                        .status(OperationStatus.STARTED)
                        .build());

        var operation = new WaitOperation("1", "test-invoke", Duration.ofSeconds(10), executionManager);

        // we currently don't check the operation status at all, so it's not blocked or failed
        var op = operation.get();
        assertNull(op);
    }
}
