// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.DistributedMapDetails;
import software.amazon.awssdk.services.lambda.model.DistributedMapOptions;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DistributedMapException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.DistributedMapResult;
import software.amazon.lambda.durable.model.DistributedMapStatus;
import software.amazon.lambda.durable.model.DistributedMapSummary;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class DistributedMapOperationTest {
    private static final String OPERATION_ID = "7";
    private static final String OPERATION_NAME = "test-distributed-map";
    private static final OperationIdentifier OPERATION_IDENTIFIER =
            OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.DISTRIBUTED_MAP);
    private static final SerDes SER_DES = new JacksonSerDes();

    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
        durableContext = mock(DurableContextImpl.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("root", ThreadType.CONTEXT));
        MockExecutionManagerSupport.stubWaitForOperationCompletion(executionManager);
    }

    @Test
    void getReturnsSummaryWhenOperationSucceeded() {
        var details = DistributedMapDetails.builder()
                .sourceExhausted(true)
                .successCount(5L)
                .failureCount(0L)
                .readCount(5L)
                .totalCount(5L)
                .distributedMapRunArn("arn:aws:lambda:us-east-1:123456789012:map-run:abc")
                .build();
        var op = succeededOperation(details);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = newOperation(TypeToken.get(DistributedMapSummary.class), DistributedMapOperation::toSummary);
        operation.onCheckpointComplete(op);

        var summary = operation.get();
        assertEquals(DistributedMapStatus.SUCCEEDED, summary.status());
        assertTrue(summary.sourceExhausted());
        assertEquals(5L, summary.successCount());
        assertEquals(0L, summary.failureCount());
        assertEquals(5L, summary.totalCount());
        assertEquals("arn:aws:lambda:us-east-1:123456789012:map-run:abc", summary.distributedMapRunArn());
        assertFalse(summary.hasFailure());
    }

    @Test
    void getReturnsResultWhenResultCollecting() {
        var details = DistributedMapDetails.builder()
                .sourceExhausted(true)
                .successCount(2L)
                .failureCount(0L)
                .readCount(2L)
                .totalCount(2L)
                .build();
        var op = succeededOperation(details);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        @SuppressWarnings({"unchecked", "rawtypes"})
        TypeToken<DistributedMapResult<Integer>> resultType = (TypeToken) TypeToken.get(DistributedMapResult.class);
        BiFunction<DistributedMapStatus, DistributedMapDetails, DistributedMapResult<Integer>> resultBuilder =
                (s, d) -> DistributedMapOperation.toResult(s, d, TypeToken.get(Integer.class), SER_DES);

        var operation = newOperation(resultType, resultBuilder);
        operation.onCheckpointComplete(op);

        var result = operation.get();
        assertEquals(DistributedMapStatus.SUCCEEDED, result.status());
        assertEquals(2L, result.successCount());
        assertTrue(result.results().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void getResolvesWithSummaryWhenOperationFailed() {
        assertGetResolvesWithSummary(OperationStatus.FAILED, DistributedMapStatus.FAILED);
    }

    @Test
    void getResolvesWithSummaryWhenOperationTimedOut() {
        assertGetResolvesWithSummary(OperationStatus.TIMED_OUT, DistributedMapStatus.TIMED_OUT);
    }

    @Test
    void getResolvesWithSummaryWhenOperationStopped() {
        assertGetResolvesWithSummary(OperationStatus.STOPPED, DistributedMapStatus.STOPPED);
    }

    @Test
    void getTerminatesWhenSucceededWithNoDetails() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = newOperation(TypeToken.get(DistributedMapSummary.class), DistributedMapOperation::toSummary);
        operation.onCheckpointComplete(op);

        assertThrows(IllegalDurableOperationException.class, operation::get);
        verify(executionManager).terminateExecution(any(IllegalDurableOperationException.class));
    }

    @Test
    void getTerminatesWhenARunExistedButNoSourceExhausted() {
        var details = DistributedMapDetails.builder()
                .distributedMapRunArn("arn:aws:lambda:us-east-1:123456789012:map-run:abc")
                .successCount(1L)
                .failureCount(0L)
                .readCount(1L)
                .build();
        var op = succeededOperation(details);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = newOperation(TypeToken.get(DistributedMapSummary.class), DistributedMapOperation::toSummary);
        operation.onCheckpointComplete(op);

        assertThrows(IllegalDurableOperationException.class, operation::get);
        verify(executionManager).terminateExecution(any(IllegalDurableOperationException.class));
    }

    @Test
    void getResolvesWhenNoRunWasCreatedAndSourceExhaustedIsAbsent() {
        var details = DistributedMapDetails.builder()
                .successCount(0L)
                .failureCount(0L)
                .readCount(0L)
                .build();
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.FAILED)
                .distributedMapDetails(details)
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = newOperation(TypeToken.get(DistributedMapSummary.class), DistributedMapOperation::toSummary);
        operation.onCheckpointComplete(op);

        var summary = operation.get();
        assertEquals(DistributedMapStatus.FAILED, summary.status());
        assertNull(summary.sourceExhausted());
    }

    @Test
    void getSuspendsUntilCheckpointCompletesThenReturnsSummary()
            throws InterruptedException, ExecutionException, TimeoutException {
        var details = DistributedMapDetails.builder()
                .sourceExhausted(true)
                .successCount(1L)
                .failureCount(0L)
                .readCount(1L)
                .totalCount(1L)
                .build();
        var op = succeededOperation(details);
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = newOperation(TypeToken.get(DistributedMapSummary.class), DistributedMapOperation::toSummary);

        // get blocks because the operation has not reached a terminal checkpoint yet
        var future = executor.submit(operation::get);
        try {
            future.get(500, TimeUnit.MILLISECONDS);
            fail();
        } catch (TimeoutException e) {
            // feed the terminal checkpoint, which unblocks get and resolves the summary
            operation.onCheckpointComplete(op);
            var summary = future.get();
            assertEquals(DistributedMapStatus.SUCCEEDED, summary.status());
            assertEquals(1L, summary.successCount());
        }
    }

    // A terminal-failed operation resolves with its summary so the counts are reachable.
    private void assertGetResolvesWithSummary(OperationStatus status, DistributedMapStatus expected) {
        var details = DistributedMapDetails.builder()
                .sourceExhausted(false)
                .successCount(3L)
                .failureCount(2L)
                .readCount(6L)
                .build();
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(status)
                .distributedMapDetails(details)
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = newOperation(TypeToken.get(DistributedMapSummary.class), DistributedMapOperation::toSummary);
        operation.onCheckpointComplete(op);

        var summary = operation.get();
        assertEquals(expected, summary.status());
        assertFalse(summary.sourceExhausted());
        assertEquals(3L, summary.successCount());
        assertEquals(2L, summary.failureCount());
        assertEquals(6L, summary.readCount());
        assertTrue(summary.hasFailure());
        assertThrows(DistributedMapException.class, summary::throwIfError);
    }

    @Test
    void replayPollsWhileTheOperationIsPending() {
        assertReplayPolls(OperationStatus.PENDING);
    }

    @Test
    void replayPollsWhileTheOperationIsStarted() {
        assertReplayPolls(OperationStatus.STARTED);
    }

    @Test
    void pollingContinuesWhenPendingBecomesStarted() {
        var pending = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.DISTRIBUTED_MAP)
                .subType(OperationSubType.DISTRIBUTED_MAP.getValue())
                .status(OperationStatus.PENDING)
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(pending);
        var started = pending.toBuilder().status(OperationStatus.STARTED).build();
        when(executionManager.pollForOperationUpdates(OPERATION_ID))
                .thenReturn(CompletableFuture.completedFuture(started), new CompletableFuture<>());

        newOperation(TypeToken.get(DistributedMapSummary.class), DistributedMapOperation::toSummary)
                .execute();

        verify(executionManager, times(2)).pollForOperationUpdates(OPERATION_ID);
    }

    @Test
    void failedRunErrorIsReportedToPlugins() {
        var error = ErrorObject.builder()
                .errorType("SourceReadError")
                .errorMessage("object not found")
                .build();
        var op = Operation.builder()
                .id(OPERATION_ID)
                .type(OperationType.DISTRIBUTED_MAP)
                .status(OperationStatus.FAILED)
                .distributedMapDetails(
                        DistributedMapDetails.builder().error(error).build())
                .build();

        var extracted = BaseDurableOperation.extractErrorFromOperation(op);

        var exception = assertInstanceOf(DurableOperationException.class, extracted);
        assertEquals(error, exception.getErrorObject());
    }

    // A non-terminal replay waits for the terminal outcome rather than terminating the execution.
    private void assertReplayPolls(OperationStatus status) {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.DISTRIBUTED_MAP)
                .subType(OperationSubType.DISTRIBUTED_MAP.getValue())
                .status(status)
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        newOperation(TypeToken.get(DistributedMapSummary.class), DistributedMapOperation::toSummary)
                .execute();

        verify(executionManager).pollForOperationUpdates(OPERATION_ID);
        verify(executionManager, never()).terminateExecution(any());
    }

    private <R> DistributedMapOperation<R> newOperation(
            TypeToken<R> resultType, BiFunction<DistributedMapStatus, DistributedMapDetails, R> resultBuilder) {
        return new DistributedMapOperation<>(
                OPERATION_IDENTIFIER,
                DistributedMapOptions.builder().build(),
                resultType,
                SER_DES,
                resultBuilder,
                durableContext);
    }

    private Operation succeededOperation(DistributedMapDetails details) {
        return Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .distributedMapDetails(details)
                .build();
    }
}
