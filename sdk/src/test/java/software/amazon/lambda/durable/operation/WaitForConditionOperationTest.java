// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.WaitForConditionConfig;
import software.amazon.lambda.durable.WaitForConditionDecision;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.WaitForConditionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class WaitForConditionOperationTest {

    private static final String OPERATION_ID = "1";
    private static final String OPERATION_NAME = "test-wait-for-condition";
    private static final JacksonSerDes SERDES = new JacksonSerDes();

    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
        durableContext = mock(DurableContextImpl.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("handler", ThreadType.CONTEXT));
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .build());
    }

    private WaitForConditionOperation<Integer> createOperation(
            java.util.function.BiFunction<Integer, software.amazon.lambda.durable.StepContext, Integer> checkFunc,
            WaitForConditionConfig<Integer> config) {
        return new WaitForConditionOperation<>(
                OPERATION_ID, OPERATION_NAME, checkFunc, TypeToken.get(Integer.class), config, durableContext);
    }

    // ===== Replay SUCCEEDED =====

    @Test
    void replaySucceededReturnsCachedResult() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("42").build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var functionCalled = new AtomicBoolean(false);
        var config = WaitForConditionConfig.<Integer>builder(
                        (state, attempt) -> WaitForConditionDecision.stopPolling(), 0)
                .serDes(SERDES)
                .build();
        var operation = createOperation(
                (state, ctx) -> {
                    functionCalled.set(true);
                    return state;
                },
                config);

        operation.execute();

        var result = operation.get();
        assertEquals(42, result);
        assertFalse(functionCalled.get(), "Check function should not be called during SUCCEEDED replay");
    }

    // ===== Replay FAILED =====

    @Test
    void replayFailedThrowsOriginalException() {
        var originalException = new IllegalArgumentException("bad state");
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("java.lang.IllegalArgumentException")
                                .errorMessage("bad state")
                                .errorData(SERDES.serialize(originalException))
                                .stackTrace(stackTrace)
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var config = WaitForConditionConfig.<Integer>builder(
                        (state, attempt) -> WaitForConditionDecision.stopPolling(), 0)
                .serDes(SERDES)
                .build();
        var operation = createOperation((state, ctx) -> state, config);

        operation.execute();

        var thrown = assertThrows(IllegalArgumentException.class, operation::get);
        assertEquals("bad state", thrown.getMessage());
    }

    @Test
    void replayFailedFallsBackToStepFailedException() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("com.nonexistent.SomeException")
                                .errorMessage("unknown error")
                                .stackTrace(List.of("com.example.Test|method|Test.java|1"))
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var config = WaitForConditionConfig.<Integer>builder(
                        (state, attempt) -> WaitForConditionDecision.stopPolling(), 0)
                .serDes(SERDES)
                .build();
        var operation = createOperation((state, ctx) -> state, config);

        operation.execute();

        assertThrows(WaitForConditionException.class, operation::get);
    }

    // ===== Replay STARTED =====

    @Test
    void replayStartedResumesCheckLoop() throws Exception {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.STARTED)
                .stepDetails(StepDetails.builder().attempt(2).result("10").build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var functionCalled = new AtomicBoolean(false);
        var config = WaitForConditionConfig.<Integer>builder(
                        (state, attempt) -> WaitForConditionDecision.stopPolling(), 0)
                .serDes(SERDES)
                .build();
        var operation = createOperation(
                (state, ctx) -> {
                    functionCalled.set(true);
                    return state + 1;
                },
                config);

        operation.execute();

        // Give the executor thread time to run
        Thread.sleep(200);
        assertTrue(functionCalled.get(), "Check function should be re-executed for STARTED replay");
    }

    // ===== Replay READY =====

    @Test
    void replayReadyResumesCheckLoop() throws Exception {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.READY)
                .stepDetails(StepDetails.builder().attempt(1).result("5").build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var functionCalled = new AtomicBoolean(false);
        var config = WaitForConditionConfig.<Integer>builder(
                        (state, attempt) -> WaitForConditionDecision.stopPolling(), 0)
                .serDes(SERDES)
                .build();
        var operation = createOperation(
                (state, ctx) -> {
                    functionCalled.set(true);
                    return state;
                },
                config);

        operation.execute();

        Thread.sleep(200);
        assertTrue(functionCalled.get(), "Check function should be re-executed for READY replay");
    }

    // ===== Non-deterministic detection =====

    @Test
    void replayWithTypeMismatchTerminatesExecution() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name(OPERATION_NAME)
                        .type(OperationType.WAIT) // Wrong type — should be STEP
                        .status(OperationStatus.SUCCEEDED)
                        .build());

        var config = WaitForConditionConfig.<Integer>builder(
                        (state, attempt) -> WaitForConditionDecision.stopPolling(), 0)
                .serDes(SERDES)
                .build();
        var operation = createOperation((state, ctx) -> state, config);

        assertThrows(NonDeterministicExecutionException.class, operation::execute);
    }

    @Test
    void replayWithNameMismatchTerminatesExecution() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name("different-name")
                        .type(OperationType.STEP)
                        .status(OperationStatus.SUCCEEDED)
                        .build());

        var config = WaitForConditionConfig.<Integer>builder(
                        (state, attempt) -> WaitForConditionDecision.stopPolling(), 0)
                .serDes(SERDES)
                .build();
        var operation = createOperation((state, ctx) -> state, config);

        assertThrows(NonDeterministicExecutionException.class, operation::execute);
    }

    // ===== get() with null error data =====

    @Test
    void getFailedWithNullErrorDataThrowsStepFailedException() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType(RuntimeException.class.getName())
                                .errorMessage("Something went wrong")
                                .stackTrace(List.of("com.example.Test|method|Test.java|42"))
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var config = WaitForConditionConfig.<Integer>builder(
                        (state, attempt) -> WaitForConditionDecision.stopPolling(), 0)
                .serDes(SERDES)
                .build();
        var operation = createOperation((state, ctx) -> state, config);

        operation.execute();

        assertThrows(WaitForConditionException.class, operation::get);
    }
}
