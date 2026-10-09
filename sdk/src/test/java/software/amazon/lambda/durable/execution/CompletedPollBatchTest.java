// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.client.DurableExecutionClient;

class CompletedPollBatchTest {
    @ParameterizedTest
    @ValueSource(
            strings = {"ready", "terminal", "exceptional", "cancelled", "checkpoint", "same-id", "other-id", "pending"})
    void completedPollsDoNotCauseEmptyRpcButLivePollsAndUpdatesStillFlush(String mode) throws Exception {
        var client = mock(DurableExecutionClient.class);
        var starts = new AtomicInteger();
        var finishes = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ofHours(1))
                .build();
        var manager = new CheckpointManager(
                config,
                "arn:test",
                "token",
                values -> callbacks.incrementAndGet(),
                () -> {
                    starts.incrementAndGet();
                    return true;
                },
                finishes::incrementAndGet);
        var ready = Operation.builder()
                .id("watched")
                .type(OperationType.STEP)
                .status(OperationStatus.READY)
                .build();
        var terminal = ready.toBuilder().status(OperationStatus.SUCCEEDED).build();
        var liveId = mode.equals("other-id") ? "other" : "watched";
        var response = ready.toBuilder().id(liveId).build();
        when(client.checkpoint(anyString(), anyString(), anyList()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("next")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(response)
                                .build())
                        .build());
        var update = OperationUpdate.builder()
                .id("write")
                .type(OperationType.STEP)
                .action(OperationAction.START)
                .build();
        try {
            var poll = manager.pollForUpdate("watched", attempt -> Duration.ofHours(1));
            CompletableFuture<Operation> live = null;
            if (mode.equals("same-id") || mode.equals("other-id"))
                live = manager.pollForUpdate(liveId, attempt -> Duration.ofHours(1));
            switch (mode) {
                case "exceptional" -> poll.completeExceptionally(new IllegalStateException("already failed"));
                case "cancelled" -> poll.cancel(false);
                case "terminal" -> poll.complete(terminal);
                case "pending" -> {}
                default -> poll.complete(ready);
            }
            var checkpoint = mode.equals("checkpoint") ? manager.checkpoint(update) : null;
            // Force the real delayed batch now, without closing CheckpointManager (which would clear the poll map).
            // This exercises the same pending batch as its timer, without a timing-based no-extra-call assertion.
            var field = CheckpointManager.class.getDeclaredField("checkpointApiRequestDelayedBatcher");
            field.setAccessible(true);
            var dispatcher = (ApiRequestDelayedBatcher<?>) field.get(manager);
            dispatcher.shutdown();
            boolean required = live != null || checkpoint != null || mode.equals("pending");
            System.out.println("COMPLETED_POLL mode=" + mode + " APIleases=" + starts.get() + " finished="
                    + finishes.get() + " callbacks=" + callbacks.get());
            verify(client, times(required ? 1 : 0))
                    .checkpoint(eq("arn:test"), eq("token"), eq(checkpoint != null ? List.of(update) : List.of()));
            assertEquals(required ? 1 : 0, starts.get());
            assertEquals(starts.get(), finishes.get());
            assertEquals(required ? 1 : 0, callbacks.get());
            if (live != null) assertEquals(response, live.get(3, TimeUnit.SECONDS));
            if (checkpoint != null) checkpoint.get(3, TimeUnit.SECONDS);
            if (mode.equals("pending")) assertEquals(response, poll.get(3, TimeUnit.SECONDS));
            else if (mode.equals("cancelled")) assertTrue(poll.isCancelled());
            else if (mode.equals("exceptional")) assertTrue(poll.isCompletedExceptionally());
            else assertSame(mode.equals("terminal") ? terminal : ready, poll.getNow(null));
        } finally {
            manager.shutdown();
        }
    }
}
