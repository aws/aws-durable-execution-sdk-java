// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

class CheckpointBatcherTest {

    private DurableConfig config;
    private DurableExecutionClient client;
    private CheckpointBatcher batcher;
    private List<Operation> callbackOperations;

    @BeforeEach
    void setUp() {
        client = mock(DurableExecutionClient.class);
        config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ofMillis(50))
                .withPollingInterval(Duration.ofMillis(50))
                .build();

        callbackOperations = new ArrayList<>();
        batcher = new CheckpointBatcher(config, "arn:test", "token-1", callbackOperations::addAll);
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

        var future = batcher.pollForUpdate("op-1", Duration.ofMillis(100));

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
}
