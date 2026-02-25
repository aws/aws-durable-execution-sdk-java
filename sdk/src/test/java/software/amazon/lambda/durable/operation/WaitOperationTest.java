// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.WaitDetails;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;

class WaitOperationTest {
    private static final String OPERATION_ID = "2";
    private static final String CONTEXT_ID = "handler";
    private static final String OPERATION_NAME = "test-wait";
    private ExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
    }

    @Test
    void constructor_withNullDuration_shouldThrow() {
        var executionManager = mock(ExecutionManager.class);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new WaitOperation(OPERATION_ID, OPERATION_NAME, null, executionManager));

        assertEquals("Wait duration cannot be null", exception.getMessage());
    }

    @Test
    void constructor_withZeroDuration_shouldThrow() {
        var executionManager = mock(ExecutionManager.class);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new WaitOperation(OPERATION_ID, OPERATION_NAME, Duration.ofSeconds(0), executionManager));

        assertTrue(exception.getMessage().contains("Wait duration"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void constructor_withSubSecondDuration_shouldThrow() {
        var executionManager = mock(ExecutionManager.class);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new WaitOperation(OPERATION_ID, OPERATION_NAME, Duration.ofMillis(500), executionManager));

        assertTrue(exception.getMessage().contains("Wait duration"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void constructor_withValidDuration_shouldPass() {
        var executionManager = mock(ExecutionManager.class);

        var operation = new WaitOperation(OPERATION_ID, OPERATION_NAME, Duration.ofSeconds(10), executionManager);

        assertEquals(OPERATION_ID, operation.getOperationId());
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .waitDetails(WaitDetails.builder().build())
                .build();
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext(CONTEXT_ID, ThreadType.CONTEXT));
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new WaitOperation(OPERATION_ID, OPERATION_NAME, Duration.ofSeconds(10), executionManager);
        operation.onCheckpointComplete(op);

        var result = operation.get();
        assertNull(result);
    }

    @Test
    void getSucceededWhenStarted() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .build();
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext(CONTEXT_ID, ThreadType.CONTEXT));
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new WaitOperation(OPERATION_ID, OPERATION_NAME, Duration.ofSeconds(10), executionManager);
        operation.onCheckpointComplete(op);

        // we currently don't check the operation status at all, so it's not blocked or failed
        assertNull(operation.get());
    }
}
