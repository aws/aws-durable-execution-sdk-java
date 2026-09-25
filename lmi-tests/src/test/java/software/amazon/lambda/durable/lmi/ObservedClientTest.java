// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.client.DurableExecutionClient;

class ObservedClientTest {
    @Test
    void pairsFinallyExitsForSuccessfulAndFailedBackendCalls() {
        try (var capture = new TraceCapture()) {
            var backend = new Backend();
            var client = new ObservedClient(backend, TraceCapture.trace("request"));
            assertSame(backend.checkpointResult, client.checkpoint("arn", "token", List.of()));
            assertSame(backend.pollResult, client.getExecutionState("arn", "token", "marker"));
            backend.failure = new IllegalStateException("backend failed");
            assertSame(
                    backend.failure,
                    assertThrows(IllegalStateException.class, () -> client.checkpoint("arn", "token", List.of())));
            assertSame(
                    backend.failure,
                    assertThrows(
                            IllegalStateException.class, () -> client.getExecutionState("arn", "token", "marker")));
            assertPaired(capture, 4);
        }
    }

    @Test
    void overlappingClientsOnOneRequestCannotReuseCallIdsOrExitEarly() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var backend = new Backend();
        backend.entered = new CountDownLatch(2);
        backend.release = new CountDownLatch(1);
        try (var capture = new TraceCapture()) {
            try {
                var trace = TraceCapture.trace("request");
                var checkpoint =
                        executor.submit(() -> new ObservedClient(backend, trace).checkpoint("arn", "token", List.of()));
                var poll = executor.submit(
                        () -> new ObservedClient(backend, trace).getExecutionState("arn", "token", "marker"));
                assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
                assertEquals(
                        List.of("CHECKPOINT_CALL", "POLL_CALL"),
                        capture.events().stream()
                                .map(e -> (String) e.get("kind"))
                                .sorted()
                                .toList());
                backend.release.countDown();
                checkpoint.get(5, TimeUnit.SECONDS);
                poll.get(5, TimeUnit.SECONDS);
                assertPaired(capture, 2);
            } finally {
                backend.release.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertPaired(TraceCapture capture, int count) {
        var calls = new HashMap<Object, String>();
        var events = capture.events();
        assertEquals(count * 2, events.size());
        for (var event : events) {
            var kind = (String) event.get("kind");
            var id = event.get("callId");
            if (kind.endsWith("_CALL")) {
                assertTrue(id instanceof Number);
                assertEquals(null, calls.put(id, kind));
            } else {
                assertEquals(kind.replace("_EXIT", "_CALL"), calls.remove(id));
            }
        }
        assertTrue(calls.isEmpty());
        assertEquals(count, events.stream().map(e -> e.get("callId")).distinct().count());
    }

    private static final class Backend implements DurableExecutionClient {
        final CheckpointDurableExecutionResponse checkpointResult =
                CheckpointDurableExecutionResponse.builder().build();
        final GetDurableExecutionStateResponse pollResult =
                GetDurableExecutionStateResponse.builder().build();
        RuntimeException failure;
        CountDownLatch entered;
        CountDownLatch release;

        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            blockOrFail();
            return checkpointResult;
        }

        @Override
        public GetDurableExecutionStateResponse getExecutionState(String arn, String token, String marker) {
            blockOrFail();
            return pollResult;
        }

        private void blockOrFail() {
            if (failure != null) {
                throw failure;
            }
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release backend");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }
}
