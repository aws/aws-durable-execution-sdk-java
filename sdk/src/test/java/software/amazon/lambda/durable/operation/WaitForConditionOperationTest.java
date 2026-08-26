// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesRunner;
import software.amazon.lambda.durable.serde.filesystem.FileSystemSerDesStage;

class WaitForConditionOperationTest {

    private static final String DURABLE_EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test:1/durable-execution/execution/invocation";
    private static final String OPERATION_ID = "1";
    private static final String OPERATION_NAME = "test-wait-for-condition";
    private static final JacksonSerDes SERDES = new JacksonSerDes();

    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;

    @TempDir
    Path basePath;

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
            java.util.function.BiFunction<
                            Integer, software.amazon.lambda.durable.StepContext, WaitForConditionResult<Integer>>
                    checkFunc,
            WaitForConditionConfig<Integer> config) {
        return new WaitForConditionOperation<>(
                OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.WAIT_FOR_CONDITION),
                checkFunc,
                TypeToken.get(Integer.class),
                config,
                durableContext);
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
        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation(
                (state, ctx) -> {
                    functionCalled.set(true);
                    return WaitForConditionResult.stopPolling(state);
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

        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation((state, ctx) -> WaitForConditionResult.stopPolling(state), config);

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

        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation((state, ctx) -> WaitForConditionResult.stopPolling(state), config);

        operation.execute();

        assertThrows(WaitForConditionFailedException.class, operation::get);
    }

    @Test
    void forwardedFilesystemExceptionIsReboundForFirstExecutionAndReplay() {
        when(executionManager.getDurableExecutionArn()).thenReturn(DURABLE_EXECUTION_ARN);
        var serDes =
                new JacksonSerDes().then(FileSystemSerDesStage.builder(basePath).build());
        var original = new IllegalArgumentException("callback failed");
        var forwarded = forwardedCallbackFailure(serDes, original);
        var failedUpdate = new AtomicReference<OperationUpdate>();
        doAnswer(invocation -> {
                    var update = invocation.<OperationUpdate>getArgument(0);
                    if (update.action() == OperationAction.FAIL) {
                        failedUpdate.set(update);
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .when(executionManager)
                .sendOperationUpdate(any());
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);
        var config = WaitForConditionConfig.<Integer>builder()
                .initialState(0)
                .serDes(serDes)
                .build();
        var operation = createOperation(
                (state, ctx) -> {
                    throw forwarded;
                },
                config);

        operation.execute();

        verify(executionManager, timeout(5_000))
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.FAIL));
        var checkpointedError = failedUpdate.get().error();
        var waitForConditionContext = SerDesContext.forOperation(
                DURABLE_EXECUTION_ARN,
                OPERATION_ID,
                OPERATION_NAME,
                null,
                OperationType.STEP,
                OperationSubType.WAIT_FOR_CONDITION,
                SerDesPayloadKind.EXCEPTION,
                1);
        var rebound = new SerDesRunner(null)
                .deserialize(
                        serDes,
                        checkpointedError.errorData(),
                        TypeToken.get(IllegalArgumentException.class),
                        waitForConditionContext);
        assertEquals("callback failed", rebound.getMessage());

        var replayedOperation = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType(OperationSubType.WAIT_FOR_CONDITION.getValue())
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder()
                        .attempt(1)
                        .error(checkpointedError)
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(replayedOperation);
        var replay = createOperation(
                (state, ctx) -> WaitForConditionResult.stopPolling(state),
                WaitForConditionConfig.<Integer>builder()
                        .initialState(0)
                        .serDes(serDes)
                        .build());

        replay.execute();

        var thrown = assertThrows(IllegalArgumentException.class, replay::get);
        assertEquals("callback failed", thrown.getMessage());
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
        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation(
                (state, ctx) -> {
                    functionCalled.set(true);
                    return WaitForConditionResult.stopPolling(state + 1);
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
        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation(
                (state, ctx) -> {
                    functionCalled.set(true);
                    return WaitForConditionResult.stopPolling(state);
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

        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation((state, ctx) -> WaitForConditionResult.stopPolling(state), config);

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

        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation((state, ctx) -> WaitForConditionResult.stopPolling(state), config);

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

        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation((state, ctx) -> WaitForConditionResult.stopPolling(state), config);

        operation.execute();

        assertThrows(WaitForConditionFailedException.class, operation::get);
    }

    // ===== Replay PENDING =====

    @Test
    void replayPendingPollsAndResumesCheckLoop() throws Exception {
        var pendingOp = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.PENDING)
                .stepDetails(StepDetails.builder().attempt(1).result("5").build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(pendingOp);

        var readyOp = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.READY)
                .stepDetails(StepDetails.builder().attempt(1).result("5").build())
                .build();
        when(executionManager.pollForOperationUpdates(OPERATION_ID))
                .thenReturn(CompletableFuture.completedFuture(readyOp));

        var functionCalled = new AtomicBoolean(false);
        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation(
                (state, ctx) -> {
                    functionCalled.set(true);
                    return WaitForConditionResult.stopPolling(state);
                },
                config);

        operation.execute();

        Thread.sleep(200);
        assertTrue(functionCalled.get(), "Check function should be called after PENDING → READY transition");
    }

    // ===== Replay unexpected status =====

    @Test
    void replayWithUnexpectedStatusTerminatesExecution() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name(OPERATION_NAME)
                        .type(OperationType.STEP)
                        .subType("WaitForCondition")
                        .status(OperationStatus.UNKNOWN_TO_SDK_VERSION)
                        .build());

        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation((state, ctx) -> WaitForConditionResult.stopPolling(state), config);

        assertThrows(IllegalDurableOperationException.class, operation::execute);
    }

    // ===== resumeCheckLoop with null checkpoint data =====

    @Test
    void replayStartedWithNullCheckpointDataUsesInitialState() throws Exception {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.STARTED)
                .stepDetails(StepDetails.builder().attempt(0).build()) // no result set
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var receivedState = new java.util.concurrent.atomic.AtomicInteger(-1);
        var config = WaitForConditionConfig.<Integer>builder()
                .serDes(SERDES)
                .initialState(42)
                .build();
        var operation = createOperation(
                (state, ctx) -> {
                    receivedState.set(state);
                    return WaitForConditionResult.stopPolling(state);
                },
                config);

        operation.execute();

        Thread.sleep(200);
        assertEquals(42, receivedState.get(), "Should use initialState when checkpoint data is null");
    }

    // ===== resumeCheckLoop checkpoint deserialize exception =====

    @Test
    void replayStartedWithCorruptCheckpointDataThrowsSerDesException() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .status(OperationStatus.STARTED)
                .stepDetails(StepDetails.builder()
                        .attempt(1)
                        .result("not-valid-json!!!")
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var config = WaitForConditionConfig.<Integer>builder().serDes(SERDES).build();
        var operation = createOperation((state, ctx) -> WaitForConditionResult.stopPolling(state), config);

        assertThrows(SerDesException.class, operation::execute);
    }

    private CallbackFailedException forwardedCallbackFailure(SerDes serDes, RuntimeException original) {
        var sourceContext = SerDesContext.forOperation(
                DURABLE_EXECUTION_ARN,
                "callback-1",
                "callback",
                null,
                OperationType.CALLBACK,
                OperationSubType.CALLBACK,
                SerDesPayloadKind.EXCEPTION,
                null);
        var error = ErrorObject.builder()
                .errorType(original.getClass().getName())
                .errorMessage(original.getMessage())
                .errorData(new SerDesRunner(null).serialize(serDes, original, sourceContext))
                .build();
        var sourceOperation = Operation.builder()
                .id("callback-1")
                .name("callback")
                .type(OperationType.CALLBACK)
                .subType(OperationSubType.CALLBACK.getValue())
                .status(OperationStatus.FAILED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .error(error)
                        .build())
                .build();
        return new CallbackFailedException(sourceOperation, original);
    }
}
