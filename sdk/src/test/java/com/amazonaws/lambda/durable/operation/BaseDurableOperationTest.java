// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.IllegalDurableOperationException;
import com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException;
import com.amazonaws.lambda.durable.exception.SerDesException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.OperationContext;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

class BaseDurableOperationTest {

    private static final String OPERATION_ID = "1";
    private static final String OPERATION_NAME = "name";
    private static final OperationType OPERATION_TYPE = OperationType.STEP;
    private static final TypeToken<String> RESULT_TYPE = TypeToken.get(String.class);
    private static final SerDes SER_DES = new JacksonSerDes();
    private static final String RESULT = "name";

    @Test
    void getOperation() {
        ExecutionManager executionManager = mock(ExecutionManager.class);
        Operation operation = mock(Operation.class);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(operation);

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        assertEquals(OPERATION_ID, op.getOperationId());
        assertEquals(OPERATION_NAME, op.getName());
        assertEquals(OPERATION_TYPE, op.getType());
        assertEquals(RESULT, op.get());
        assertEquals(operation, op.getOperation());
    }

    @Test
    void waitForOperationCompletionThrowsIfInStep() {
        Phaser phaser = new Phaser();
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("context", ThreadType.STEP));
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder().build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        assertThrows(IllegalDurableOperationException.class, this::waitForOperationCompletion);
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        op.execute();
        verify(executionManager).terminateExecution(any(IllegalDurableOperationException.class));
    }

    @Test
    void waitForOperationCompletionThrowsIfOperationMissing() {
        Phaser phaser = new Phaser();
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("context", ThreadType.CONTEXT));
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        assertThrows(IllegalDurableOperationException.class, this::waitForOperationCompletion);
                        return RESULT;
                    }
                };

        op.get();
        verify(executionManager).terminateExecution(any(IllegalDurableOperationException.class));
    }

    @Test
    void waitForOperationCompletionWhenRunningAndReadyToComplete() {
        Phaser phaser = new Phaser(0);
        OperationContext context = new OperationContext("step", ThreadType.CONTEXT);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder().build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        waitForOperationCompletion();
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        op.execute();
        verify(executionManager).deregisterActiveThreadAndUnsetCurrentContext(context.contextId());
        verify(executionManager).registerActiveThread(context.contextId(), context.threadType());
        verify(executionManager).setCurrentContext(context.contextId(), context.threadType());
    }

    @Test
    void waitForOperationCompletionWhenAlreadyCompleted() {
        Phaser phaser = new Phaser(1);
        phaser.arrive(); // completed
        OperationContext context = new OperationContext("step", ThreadType.CONTEXT);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder().build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        waitForOperationCompletion();
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        op.execute();
        verify(executionManager, never()).deregisterActiveThreadAndUnsetCurrentContext(context.contextId());
        verify(executionManager, never()).registerActiveThread(context.contextId(), context.threadType());
        verify(executionManager, never()).setCurrentContext(context.contextId(), context.threadType());
    }

    @Test
    void markAlreadyCompleted() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder().build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        markAlreadyCompleted();
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        op.execute();
        // arrived and deregistered
        assertEquals(0, phaser.getRegisteredParties());
    }

    @Test
    void validateReplayThrowsWhenTypeMismatch() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(
                        Operation.builder().type(OperationType.CHAINED_INVOKE).build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        validateReplay(getOperation());
                        return "";
                    }
                };

        assertThrows(NonDeterministicExecutionException.class, op::get);
    }

    @Test
    void validateReplayThrowsWhenNameMismatch() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .name("another name")
                        .type(OPERATION_TYPE)
                        .build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        validateReplay(getOperation());
                        return "";
                    }
                };

        assertThrows(NonDeterministicExecutionException.class, op::get);
    }

    @Test
    void validateReplayDoesNotThrowWhenNoOperation() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        validateReplay(getOperation());
                        return "";
                    }
                };
    }

    @Test
    void validateReplayDoesNotThrowWhenNameAndTypeMatch() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .name(OPERATION_NAME)
                        .type(OPERATION_TYPE)
                        .build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        validateReplay(getOperation());
                        return "";
                    }
                };
    }

    @Test
    void deserializeResult() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .name(OPERATION_NAME)
                        .type(OPERATION_TYPE)
                        .build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        assertEquals("abc", deserializeResult(serializeResult("abc")));
                        assertEquals("", deserializeResult("\"\""));
                        assertThrows(SerDesException.class, () -> deserializeResult("x"));
                        return "";
                    }
                };
        op.get();
    }

    @Test
    void deserializeException() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .name(OPERATION_NAME)
                        .type(OPERATION_TYPE)
                        .build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        assertNull(deserializeException(ErrorObject.builder().build()));
                        assertNull(deserializeException(ErrorObject.builder()
                                .errorType("UnknownExceptionType")
                                .build()));
                        Throwable ex = deserializeException(serializeException(new RuntimeException("test exception")));
                        assertInstanceOf(RuntimeException.class, ex);
                        assertEquals("test exception", ex.getMessage());
                        return "";
                    }
                };

        op.get();
    }

    @Test
    void polling() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .name(OPERATION_NAME)
                        .type(OPERATION_TYPE)
                        .build());
        Instant now = Instant.now();
        CompletableFuture<Void> future = new CompletableFuture<>();

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        pollForOperationUpdates(now, Duration.ofSeconds(1));
                        pollUntilReady(future, now, Duration.ofSeconds(1));
                    }

                    @Override
                    public String get() {

                        return "";
                    }
                };

        op.execute();
        verify(executionManager).pollForOperationUpdates(OPERATION_ID, now, Duration.ofSeconds(1));
        verify(executionManager).pollUntilReady(OPERATION_ID, future, now, Duration.ofSeconds(1));
    }

    @Test
    void sendOperationUpdate() {
        Phaser phaser = new Phaser(1);
        OperationContext context = new OperationContext("step", ThreadType.STEP);
        ExecutionManager executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(context);
        when(executionManager.startPhaser(OPERATION_ID)).thenReturn(phaser);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .name(OPERATION_NAME)
                        .type(OPERATION_TYPE)
                        .build());
        var update = OperationUpdate.builder();
        when(executionManager.sendOperationUpdate(update.build())).thenReturn(CompletableFuture.completedFuture(null));

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        sendOperationUpdate(update);
                        sendOperationUpdateAsync(update);
                    }

                    @Override
                    public String get() {

                        return "";
                    }
                };

        op.execute();
        verify(executionManager, times(2)).sendOperationUpdate(update.build());
    }
}
