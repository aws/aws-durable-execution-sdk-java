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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void pollForUpdate_completesWhenOperationReturned() throws Exception {
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

        var future = batcher.pollForUpdate("op-1");

        assertFalse(future.isDone());

        // Wait for polling to trigger checkpoint
        var result = future.get(300, TimeUnit.MILLISECONDS);

        assertEquals(operation, result);
        assertEquals(1, callbackOperations.size());
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
}
