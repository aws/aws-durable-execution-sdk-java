// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

class BaseDurableOperationTest {

    private static final String OPERATION_ID = "1";
    private static final String CONTEXT_ID = "1-step";
    private static final String OPERATION_NAME = "name";
    private static final Operation OPERATION = Operation.builder().build();
    private static final OperationType OPERATION_TYPE = OperationType.STEP;
    private static final TypeToken<String> RESULT_TYPE = TypeToken.get(String.class);
    private static final SerDes SER_DES = new JacksonSerDes();
    private static final String RESULT = "name";
    private final ExecutorService internalExecutor = Executors.newFixedThreadPool(2);

    private ExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext(CONTEXT_ID, ThreadType.CONTEXT));
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(OPERATION);
    }

    @Test
    void getOperation() {
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
        assertEquals(OPERATION, op.getOperation());
    }

    @Test
    void waitForOperationCompletionThrowsIfInStep() {
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("context", ThreadType.STEP));

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
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);
        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        markAlreadyCompleted();
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
    void waitForOperationCompletionWhenRunningAndReadyToComplete()
            throws InterruptedException, ExecutionException, TimeoutException {
        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {}

                    @Override
                    public String get() {
                        waitForOperationCompletion();
                        return RESULT;
                    }
                };

        // call get in a separate thread which will be blocked
        var future = internalExecutor.submit(op::get);
        // wait for execute to be blocked by the completionFuture and then feed the completion event
        try {
            future.get(500, TimeUnit.MILLISECONDS);
            fail();
        } catch (TimeoutException e) {
            op.onCheckpointComplete(
                    Operation.builder().status(OperationStatus.SUCCEEDED).build());
            assertEquals(RESULT, future.get());
            verify(executionManager).deregisterActiveThreadAndUnsetCurrentContext(CONTEXT_ID);
            verify(executionManager).registerActiveThread(CONTEXT_ID, ThreadType.CONTEXT);
            verify(executionManager).setCurrentContext(CONTEXT_ID, ThreadType.CONTEXT);
        }
    }

    @Test
    void waitForOperationCompletionWhenAlreadyCompleted() {
        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        markAlreadyCompleted();
                        waitForOperationCompletion();
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        op.execute();
        verify(executionManager, never()).deregisterActiveThreadAndUnsetCurrentContext(CONTEXT_ID);
        verify(executionManager, never()).registerActiveThread(CONTEXT_ID, ThreadType.CONTEXT);
        verify(executionManager).setCurrentContext(CONTEXT_ID, ThreadType.CONTEXT);
    }

    @Test
    void markAlreadyCompleted() {
        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        markAlreadyCompleted();
                        // completion future should be complete
                        assertTrue(this.isOperationCompleted());
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        op.execute();
    }

    @Test
    void validateReplayThrowsWhenTypeMismatch() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(
                        Operation.builder().type(OperationType.CHAINED_INVOKE).build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        validateReplay(getOperation());
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        assertThrows(NonDeterministicExecutionException.class, op::execute);
    }

    @Test
    void validateReplayThrowsWhenNameMismatch() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .name("another name")
                        .type(OPERATION_TYPE)
                        .build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        validateReplay(getOperation());
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        assertThrows(NonDeterministicExecutionException.class, op::execute);
    }

    @Test
    void validateReplayDoesNotThrowWhenNoOperation() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        validateReplay(getOperation());
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };
        op.execute();
    }

    @Test
    void validateReplayDoesNotThrowWhenNameAndTypeMatch() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .name(OPERATION_NAME)
                        .type(OPERATION_TYPE)
                        .build());

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        validateReplay(getOperation());
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };
        op.execute();
    }

    @Test
    void deserializeResult() {
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
        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        pollForOperationUpdates();
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        op.execute();
        verify(executionManager).pollForOperationUpdates(OPERATION_ID);
    }

    @Test
    void sendOperationUpdate() {
        var update = OperationUpdate.builder();

        BaseDurableOperation<String> op =
                new BaseDurableOperation<>(
                        OPERATION_ID, OPERATION_NAME, OPERATION_TYPE, RESULT_TYPE, SER_DES, executionManager) {
                    @Override
                    public void execute() {
                        sendOperationUpdate(update);
                    }

                    @Override
                    public String get() {
                        return RESULT;
                    }
                };

        op.execute();
        verify(executionManager, times(1)).sendOperationUpdate(update.build());
    }
}
