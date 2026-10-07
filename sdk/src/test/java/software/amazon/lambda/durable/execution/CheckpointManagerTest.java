// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.PollingStrategies;

class CheckpointManagerTest {

    private DurableConfig config;
    private DurableExecutionClient client;
    private CheckpointManager batcher;
    private List<Operation> callbackOperations;

    @BeforeEach
    void setUp() {
        client = mock(DurableExecutionClient.class);
        config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ofMillis(50))
                .withPollingStrategy(PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(50), 2.0, JitterStrategy.FULL, Duration.ofSeconds(10)))
                .build();

        callbackOperations = new ArrayList<>();
        batcher = new CheckpointManager(config, "arn:test", "token-1", callbackOperations::addAll);
    }

    @Test
    void checkpoint_sendsUpdateAndReturnsCompletedFuture() throws Exception {
        var update = OperationUpdate.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .action(OperationAction.START)
                .build();

        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .build());

        var future = batcher.checkpoint(update);

        // Wait for batch to flush
        future.get(200, TimeUnit.MILLISECONDS);

        verify(client).checkpoint(eq("arn:test"), eq("token-1"), anyList());
        assertTrue(future.isDone());
    }

    @Test
    void checkpoint_skipsApiCallWhenExecutionAlreadyCompleted() throws Exception {
        var finishCheckpointProcessing = mock(Runnable.class);
        var guardedBatcher = new CheckpointManager(
                config,
                "arn:test",
                "token-1",
                callbackOperations::addAll,
                () -> false,
                finishCheckpointProcessing,
                () -> {});
        var update = OperationUpdate.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .action(OperationAction.START)
                .build();

        var error = assertThrows(
                Exception.class, () -> guardedBatcher.checkpoint(update).get(200, TimeUnit.MILLISECONDS));
        assertInstanceOf(SuspendExecutionException.class, error.getCause());

        verifyNoInteractions(client);
        verify(finishCheckpointProcessing, never()).run();
    }

    @Test
    void pollForUpdate_completesWhenOperationReturned() throws Exception {
        var checkpointStarted = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        when(client.checkpoint(anyString(), anyString(), anyList())).thenAnswer(ignored -> {
            checkpointStarted.countDown();
            releaseResponse.await();
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken("token-2")
                    .newExecutionState(CheckpointUpdatedExecutionState.builder()
                            .operations(List.of(operation))
                            .build())
                    .build();
        });

        try {
            var future = batcher.pollForUpdate("op-1");
            assertTrue(checkpointStarted.await(300, TimeUnit.MILLISECONDS));
            assertFalse(future.isDone(), "polling stays pending until the backend returns the operation");
            releaseResponse.countDown();

            var result = future.get(300, TimeUnit.MILLISECONDS);
            assertEquals(operation, result);
            assertEquals(1, callbackOperations.size());
        } finally {
            releaseResponse.countDown();
            batcher.shutdown();
        }
    }

    @Test
    void pollForUpdate_doesNotCompleteWhenDifferentOperationReturned() throws Exception {
        var operation = Operation.builder()
                .id("op-2")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build());

        var future = batcher.pollForUpdate("op-1");

        // Should timeout since op-1 never returned
        assertThrows(TimeoutException.class, () -> future.get(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void pollForUpdate_handlesMultiplePollers() throws Exception {
        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build());

        var future1 = batcher.pollForUpdate("op-1");
        var future2 = batcher.pollForUpdate("op-1");
        var future3 = batcher.pollForUpdate("op-1");

        var result1 = future1.get(300, TimeUnit.MILLISECONDS);
        var result2 = future2.get(300, TimeUnit.MILLISECONDS);
        var result3 = future3.get(300, TimeUnit.MILLISECONDS);

        assertEquals(operation, result1);
        assertEquals(operation, result2);
        assertEquals(operation, result3);
    }

    @Test
    void shutdown_completesAllPendingPollersWithException() {
        var future1 = batcher.pollForUpdate("op-1");
        var future2 = batcher.pollForUpdate("op-2");

        batcher.shutdown();

        assertTrue(future1.isCompletedExceptionally());
        assertTrue(future2.isCompletedExceptionally());

        assertThrows(Exception.class, future1::join);
        assertThrows(Exception.class, future2::join);
    }

    @Test
    void shutdown_waitsForPendingCheckpoints() throws Exception {
        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .build());

        var future = batcher.checkpoint(OperationUpdate.builder()
                .id("op-1")
                .action(OperationAction.START)
                .type(OperationType.STEP)
                .build());

        batcher.shutdown();

        assertTrue(future.isDone());
        verify(client, atLeastOnce()).checkpoint(anyString(), anyString(), anyList());
    }

    @Test
    void fetchAllPages_retrievesAllOperations() {
        var op1 = Operation.builder().id("op-1").build();
        var op2 = Operation.builder().id("op-2").build();
        var op3 = Operation.builder().id("op-3").build();

        when(client.getExecutionState(eq("arn:test"), eq("token-1"), eq("marker-1")))
                .thenReturn(GetDurableExecutionStateResponse.builder()
                        .operations(List.of(op2))
                        .nextMarker("marker-2")
                        .build());

        when(client.getExecutionState(eq("arn:test"), eq("token-1"), eq("marker-2")))
                .thenReturn(GetDurableExecutionStateResponse.builder()
                        .operations(List.of(op3))
                        .nextMarker(null)
                        .build());

        var state = CheckpointUpdatedExecutionState.builder()
                .operations(List.of(op1))
                .nextMarker("marker-1")
                .build();

        var result = batcher.fetchAllPages(state);

        assertEquals(3, result.size());
        assertEquals("op-1", result.get(0).id());
        assertEquals("op-2", result.get(1).id());
        assertEquals("op-3", result.get(2).id());
    }

    @Test
    void fetchAllPages_handlesNullState() {
        var result = batcher.fetchAllPages(null);

        assertEquals(0, result.size());
        verify(client, never()).getExecutionState(anyString(), anyString(), anyString());
    }

    @Test
    void fetchAllPages_handlesEmptyMarker() {
        var state = CheckpointUpdatedExecutionState.builder()
                .operations(List.of(Operation.builder().id("op-1").build()))
                .nextMarker("")
                .build();

        var result = batcher.fetchAllPages(state);

        assertEquals(1, result.size());
        verify(client, never()).getExecutionState(anyString(), anyString(), anyString());
    }

    @Test
    void checkpoint_updatesCheckpointToken() throws Exception {
        when(client.checkpoint(anyString(), eq("token-1"), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .build());

        when(client.checkpoint(anyString(), eq("token-2"), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-3")
                        .build());

        batcher.checkpoint(OperationUpdate.builder()
                        .id("op-1")
                        .type(OperationType.STEP)
                        .action(OperationAction.SUCCEED)
                        .build())
                .get(200, TimeUnit.MILLISECONDS);

        batcher.checkpoint(OperationUpdate.builder()
                        .id("op-2")
                        .type(OperationType.STEP)
                        .action(OperationAction.START)
                        .build())
                .get(200, TimeUnit.MILLISECONDS);

        verify(client).checkpoint(eq("arn:test"), eq("token-1"), anyList());
        verify(client).checkpoint(eq("arn:test"), eq("token-2"), anyList());
    }

    @Test
    void pollForUpdate_withCustomDelay() throws Exception {
        var operation =
                Operation.builder().id("op-1").status(OperationStatus.SUCCEEDED).build();

        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build());

        var future = batcher.pollForUpdate("op-1", Instant.now().plusMillis(100));

        var result = future.get(300, TimeUnit.MILLISECONDS);

        assertEquals(operation, result);
    }

    @Test
    void checkpoint_filtersNullUpdates() throws Exception {
        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .build());

        // Submit null (from polling) and real update
        batcher.pollForUpdate("op-1");
        batcher.checkpoint(OperationUpdate.builder()
                        .id("op-2")
                        .type(OperationType.STEP)
                        .action(OperationAction.START)
                        .build())
                .get(200, TimeUnit.MILLISECONDS);

        verify(client).checkpoint(eq("arn:test"), eq("token-1"), argThat(list -> {
            // Should only contain non-null update
            return list.stream().noneMatch(u -> u == null);
        }));
    }

    // --- Polling backoff and jitter tests ---

    @Test
    void pollForUpdate_withBackoffAndNoJitter_completesWhenOperationReturned() throws Exception {
        var backoffConfig = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPollingStrategy(PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(10), 2.0, JitterStrategy.NONE, Duration.ofSeconds(10)))
                .withCheckpointDelay(Duration.ofMillis(50))
                .build();
        var backoffBatcher = new CheckpointManager(backoffConfig, "arn:test", "token-1", callbackOperations::addAll);

        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build());

        var future = backoffBatcher.pollForUpdate("op-1");
        var result = future.get(500, TimeUnit.MILLISECONDS);

        assertEquals(operation, result);
    }

    @Test
    void pollForUpdate_withBackoff_pollsMultipleTimesBeforeCompletion() throws Exception {
        var backoffConfig = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPollingStrategy(PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(10), 1.5, JitterStrategy.NONE, Duration.ofSeconds(10)))
                .withCheckpointDelay(Duration.ofMillis(50))
                .build();
        var backoffBatcher = new CheckpointManager(backoffConfig, "arn:test", "token-1", callbackOperations::addAll);

        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        var callCount = new AtomicInteger(0);
        when(client.checkpoint(anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            // Return the operation on the 3rd call
            if (count >= 3) {
                return CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build();
            }
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken("token-1")
                    .build();
        });

        var future = backoffBatcher.pollForUpdate("op-1");
        var result = future.get(1000, TimeUnit.MILLISECONDS);

        assertEquals(operation, result);
        assertTrue(callCount.get() >= 3, "Expected at least 3 checkpoint calls, got " + callCount.get());
    }

    @Test
    void pollForUpdate_withCustomDelay_ignoresBackoffConfig() throws Exception {
        var backoffConfig = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPollingStrategy(PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(10), 100.0, JitterStrategy.NONE, Duration.ofSeconds(10)))
                .withCheckpointDelay(Duration.ofMillis(50))
                .build();
        var backoffBatcher = new CheckpointManager(backoffConfig, "arn:test", "token-1", callbackOperations::addAll);

        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        var callCount = new AtomicInteger(0);
        when(client.checkpoint(anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            if (count >= 3) {
                return CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build();
            }
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken("token-1")
                    .build();
        });

        // Use explicit delay (fixed interval) — should NOT apply backoff
        var future = backoffBatcher.pollForUpdate("op-1", Instant.now().plusMillis(20));
        var result = future.get(1000, TimeUnit.MILLISECONDS);

        assertEquals(operation, result);
        // With fixed interval of 20ms, should complete quickly despite backoffRate=100
        assertTrue(callCount.get() >= 3);
    }

    @Test
    void pollForUpdate_withFullJitter_completesWhenOperationReturned() throws Exception {
        var jitterConfig = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPollingStrategy(PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(10), 2.0, JitterStrategy.FULL, Duration.ofSeconds(10)))
                .withCheckpointDelay(Duration.ofMillis(50))
                .build();
        var jitterBatcher = new CheckpointManager(jitterConfig, "arn:test", "token-1", callbackOperations::addAll);

        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build());

        var future = jitterBatcher.pollForUpdate("op-1");
        var result = future.get(500, TimeUnit.MILLISECONDS);

        assertEquals(operation, result);
    }

    @Test
    void pollForUpdate_withHalfJitter_completesWhenOperationReturned() throws Exception {
        var jitterConfig = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPollingStrategy(PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(10), 2.0, JitterStrategy.HALF, Duration.ofSeconds(10)))
                .withCheckpointDelay(Duration.ofMillis(50))
                .build();
        var jitterBatcher = new CheckpointManager(jitterConfig, "arn:test", "token-1", callbackOperations::addAll);

        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build());

        var future = jitterBatcher.pollForUpdate("op-1");
        var result = future.get(500, TimeUnit.MILLISECONDS);

        assertEquals(operation, result);
    }

    @Test
    void pollForUpdate_defaultConfig_appliesBackoffAndJitter() throws Exception {
        // Default config: pollingInterval=1000ms, backoffRate=2.0, jitter=FULL
        // Use small interval to keep test fast
        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build());

        // setUp() batcher uses pollingInterval=50ms, backoffRate=2.0 (default), jitter=FULL (default)
        var future = batcher.pollForUpdate("op-1");
        var result = future.get(500, TimeUnit.MILLISECONDS);

        assertEquals(operation, result);
    }

    @Test
    void pollForUpdate_withBackoff_delayGrowsAcrossAttempts() throws Exception {
        // Use NONE jitter so delays are deterministic: base * backoffRate^attempt
        // base=10ms, rate=2.0 → attempt 0: 10ms, attempt 1: 20ms, attempt 2: 40ms
        var backoffConfig = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPollingStrategy(PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(10), 2.0, JitterStrategy.NONE, Duration.ofSeconds(10)))
                .withCheckpointDelay(Duration.ofMillis(50))
                .build();
        var backoffBatcher = new CheckpointManager(backoffConfig, "arn:test", "token-1", callbackOperations::addAll);

        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        var callCount = new AtomicInteger(0);
        var callTimestamps = new ArrayList<Long>();
        when(client.checkpoint(anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            callTimestamps.add(System.nanoTime());
            int count = callCount.incrementAndGet();
            if (count >= 4) {
                return CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build();
            }
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken("token-1")
                    .build();
        });

        var future = backoffBatcher.pollForUpdate("op-1");
        future.get(2000, TimeUnit.MILLISECONDS);

        assertTrue(callCount.get() >= 4, "Expected at least 4 calls, got " + callCount.get());
        // Verify that later intervals are generally longer than earlier ones
        // (not exact due to scheduling, but the trend should hold)
        if (callTimestamps.size() >= 4) {
            var interval1 = callTimestamps.get(1) - callTimestamps.get(0);
            var interval3 = callTimestamps.get(3) - callTimestamps.get(2);
            assertTrue(
                    interval3 >= interval1,
                    "Later polling intervals should be >= earlier ones with backoff. "
                            + "interval1=" + Duration.ofNanos(interval1).toMillis()
                            + "ms, interval3=" + Duration.ofNanos(interval3).toMillis() + "ms");
        }
    }

    @Test
    void pollForUpdate_withFixedDelay_intervalsAreConsistent() throws Exception {
        var fixedConfig = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPollingStrategy(PollingStrategies.fixedDelay(Duration.ofMillis(50)))
                .withCheckpointDelay(Duration.ofMillis(50))
                .build();
        var fixedBatcher = new CheckpointManager(fixedConfig, "arn:test", "token-1", callbackOperations::addAll);

        var operation = Operation.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();

        var callCount = new AtomicInteger(0);
        var callTimestamps = new ArrayList<Long>();
        when(client.checkpoint(anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            callTimestamps.add(System.nanoTime());
            int count = callCount.incrementAndGet();
            if (count >= 5) {
                return CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("token-2")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(operation))
                                .build())
                        .build();
            }
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken("token-1")
                    .build();
        });

        var future = fixedBatcher.pollForUpdate("op-1");
        future.get(2000, TimeUnit.MILLISECONDS);

        assertTrue(callCount.get() >= 5, "Expected at least 5 calls, got " + callCount.get());

        // Verify intervals are roughly consistent (no exponential growth)
        if (callTimestamps.size() >= 5) {
            var intervals = new ArrayList<Long>();
            for (int i = 1; i < callTimestamps.size(); i++) {
                intervals.add(TimeUnit.NANOSECONDS.toMillis(callTimestamps.get(i) - callTimestamps.get(i - 1)));
            }
            var maxInterval =
                    intervals.stream().mapToLong(Long::longValue).max().orElse(0);
            var minInterval =
                    intervals.stream().mapToLong(Long::longValue).min().orElse(0);
            // With fixed delay of 50ms, the spread between min and max should be small
            // (no exponential growth). Allow generous tolerance for scheduling jitter.
            assertTrue(
                    maxInterval - minInterval < 150,
                    "Fixed delay intervals should be roughly consistent. min=" + minInterval + "ms, max=" + maxInterval
                            + "ms, intervals=" + intervals);
        }
    }

    // --- Checkpoint error classification wiring tests ---

    @Test
    void checkpointBatch_nonRetryableError_throwsUnrecoverable() {
        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenThrow(AwsServiceException.builder()
                        .message("KMSAccessDeniedException: Lambda was unable to decrypt the environment variables")
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("KMSAccessDeniedException")
                                .errorMessage("Lambda was unable to decrypt the environment variables")
                                .sdkHttpResponse(SdkHttpResponse.builder()
                                        .statusCode(502)
                                        .build())
                                .build())
                        .statusCode(502)
                        .build());

        var future = batcher.checkpoint(OperationUpdate.builder()
                .id("op-1")
                .type(OperationType.STEP)
                .action(OperationAction.START)
                .build());

        var ex = assertThrows(Exception.class, () -> future.get(200, TimeUnit.MILLISECONDS));
        assertInstanceOf(UnrecoverableDurableExecutionException.class, ex.getCause());
    }

    // --- Checkpoint token revocation tests (a checkpoint response with no checkpoint token) ---

    @Test
    void checkpointBatch_nonTerminalTokenWithdrawal_suspendsAndRejectsFurtherRequests() {
        var signalSuspension = mock(Runnable.class);
        var revocableBatcher = new CheckpointManager(
                config, "arn:test", "token-1", callbackOperations::addAll, () -> true, () -> {}, signalSuspension);
        var update = stepUpdate("op-1");
        when(client.checkpoint(any(), any(), any())).thenReturn(missingTokenResponse(update));
        var poller = revocableBatcher.pollForUpdate("op-1", Instant.now().plusSeconds(60));
        try {
            assertSuspended(revocableBatcher.checkpoint(update));
            assertSuspended(poller);
            assertTrue(revocableBatcher.isCheckpointTokenRevoked());
            verify(signalSuspension).run();
            assertWithdrawalStopsFurtherRequests(revocableBatcher, update);
        } finally {
            revocableBatcher.shutdown();
        }
    }

    @Test
    void checkpointBatch_terminalTokenWithdrawal_completesAndRejectsFurtherRequests() throws Exception {
        var signalSuspension = mock(Runnable.class);
        var revocableBatcher = new CheckpointManager(
                config, "arn:test", "token-1", callbackOperations::addAll, () -> true, () -> {}, signalSuspension);
        var update = OperationUpdate.builder()
                .id("exec-op")
                .type(OperationType.EXECUTION)
                .action(OperationAction.SUCCEED)
                .payload("\"result\"")
                .build();
        when(client.checkpoint(any(), any(), any())).thenReturn(missingTokenResponse(update));
        var poller = revocableBatcher.pollForUpdate("exec-op", Instant.now().plusSeconds(60));
        try {
            revocableBatcher.checkpoint(update).get(1, TimeUnit.SECONDS);
            assertSuspended(poller);
            assertFalse(revocableBatcher.isCheckpointTokenRevoked());
            verify(signalSuspension, never()).run();
            assertWithdrawalStopsFurtherRequests(revocableBatcher, update);
        } finally {
            revocableBatcher.shutdown();
        }
    }

    @Test
    void shutdown_rejectsLaterPollRegistration() throws Exception {
        batcher.shutdown();

        var poller = batcher.pollForUpdate("late", Instant.now().plusSeconds(60));
        assertTrue(poller.isCompletedExceptionally());
        var error = assertThrows(Exception.class, () -> poller.get(1, TimeUnit.SECONDS));
        assertInstanceOf(SuspendExecutionException.class, error.getCause());
        batcher.shutdown();
        verifyNoInteractions(client);
    }

    @Test
    @Timeout(10)
    void checkpointBatch_revokedToken_flushesLongDelayedCheckpointsAcrossMultipleBatches() throws Exception {
        var revocationStarted = new CountDownLatch(1);
        var returnRevokedResponse = new CountDownLatch(1);
        var delayedConfig = longDelayedConfig();
        var revocableBatcher = new CheckpointManager(delayedConfig, "arn:test", "token-1", callbackOperations::addAll);
        revokeAfterRelease(revocationStarted, returnRevokedResponse);
        try {
            var poller = revocableBatcher.pollForUpdate("poll", Instant.now());
            await(revocationStarted);
            var queued = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 401; i++) {
                queued.add(revocableBatcher.checkpoint(stepUpdate("op-" + i)));
            }
            returnRevokedResponse.countDown();
            assertSuspended(poller);
            for (var future : queued) {
                assertSuspended(future);
            }
            verify(client, times(1)).checkpoint(anyString(), anyString(), anyList());
            verify(client, never()).getExecutionState(any(), any(), any());
        } finally {
            returnRevokedResponse.countDown();
            revocableBatcher.shutdown();
        }
    }

    @Test
    @Timeout(10)
    void checkpoint_withdrawalBetweenTokenCheckAndSubmission_flushesLateRequest() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var returnRevokedResponse = new CountDownLatch(1);
        var submissionPaused = new CountDownLatch(1);
        var resumeSubmission = new CountDownLatch(1);
        var delayedConfig = blockedCheckpointConfig(submissionPaused, resumeSubmission);
        var revocableBatcher = new CheckpointManager(delayedConfig, "arn:test", "token-1", callbackOperations::addAll);
        var caller = Executors.newSingleThreadExecutor();
        revokeAfterRelease(requestStarted, returnRevokedResponse);
        try {
            var poller = revocableBatcher.pollForUpdate("poll", Instant.now());
            await(requestStarted);
            var submission = caller.submit(() -> revocableBatcher.checkpoint(stepUpdate("late")));
            await(submissionPaused);
            returnRevokedResponse.countDown();
            assertSuspended(poller);
            revocableBatcher.shutdown();
            resumeSubmission.countDown();
            assertSuspended(submission.get(1, TimeUnit.SECONDS));
            verify(client, times(1)).checkpoint(anyString(), anyString(), anyList());
            verify(client, never()).getExecutionState(any(), any(), any());
        } finally {
            returnRevokedResponse.countDown();
            resumeSubmission.countDown();
            caller.shutdownNow();
            revocableBatcher.shutdown();
        }
    }

    @Test
    void fetchAllPages_nonRetryableError_throwsUnrecoverable() {
        when(client.getExecutionState(eq("arn:test"), eq("token-1"), eq("marker-1")))
                .thenThrow(AwsServiceException.builder()
                        .message("KMSAccessDeniedException: Lambda was unable to decrypt the environment variables")
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("KMSAccessDeniedException")
                                .errorMessage("Lambda was unable to decrypt the environment variables")
                                .sdkHttpResponse(SdkHttpResponse.builder()
                                        .statusCode(502)
                                        .build())
                                .build())
                        .statusCode(502)
                        .build());

        var state = CheckpointUpdatedExecutionState.builder()
                .operations(List.of(Operation.builder().id("op-1").build()))
                .nextMarker("marker-1")
                .build();

        assertThrows(UnrecoverableDurableExecutionException.class, () -> batcher.fetchAllPages(state));
    }

    private CheckpointDurableExecutionResponse missingTokenResponse(OperationUpdate update) {
        var operation = Operation.builder()
                .id(update.id())
                .type(update.type())
                .status(OperationStatus.SUCCEEDED)
                .build();
        return CheckpointDurableExecutionResponse.builder()
                .newExecutionState(CheckpointUpdatedExecutionState.builder()
                        .operations(operation)
                        .nextMarker("unused")
                        .build())
                .build();
    }

    private void assertWithdrawalStopsFurtherRequests(CheckpointManager manager, OperationUpdate update) {
        assertTrue(callbackOperations.isEmpty());
        var lateCheckpoint = manager.checkpoint(update);
        var latePoller = manager.pollForUpdate("late", Instant.now().plusSeconds(60));
        assertTrue(lateCheckpoint.isCompletedExceptionally());
        assertTrue(latePoller.isCompletedExceptionally());
        assertSuspended(lateCheckpoint);
        assertSuspended(latePoller);
        verify(client, times(1)).checkpoint(any(), any(), any());
        verify(client, never()).getExecutionState(any(), any(), any());
    }

    private DurableConfig longDelayedConfig() {
        return DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ofHours(1))
                .build();
    }

    private DurableConfig blockedCheckpointConfig(CountDownLatch paused, CountDownLatch resume) {
        var blockedConfig = spy(longDelayedConfig());
        doAnswer(invocation -> {
                    paused.countDown();
                    await(resume);
                    return Duration.ofHours(1);
                })
                .when(blockedConfig)
                .getCheckpointDelay();
        return blockedConfig;
    }

    private void revokeAfterRelease(CountDownLatch started, CountDownLatch release) {
        when(client.checkpoint(anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            started.countDown();
            await(release);
            return CheckpointDurableExecutionResponse.builder().build();
        });
    }

    private OperationUpdate stepUpdate(String id) {
        return OperationUpdate.builder()
                .id(id)
                .type(OperationType.STEP)
                .action(OperationAction.START)
                .build();
    }

    private void assertSuspended(CompletableFuture<?> future) {
        var error = assertThrows(Exception.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(SuspendExecutionException.class, error.getCause());
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for the test latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
