// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class OwnedCompletionAdjacentTest {
    @Test
    void acknowledgedDeliveryFailureIsRetainedByLaterOwnedFatal() throws Exception {
        run(true, true);
    }

    @Test
    void acknowledgedDeliveryFailureStillOutranksOrdinaryOwnedFailure() throws Exception {
        run(true, false);
    }

    @Test
    void callerCutoffRetainsLaterSerializerFailureAndOwnedFailure() throws Exception {
        run(false, false);
    }

    @Test
    void callerCutoffDoesNotDuplicateTheSerializerUsedAsShutdownFailure() throws Exception {
        run(false, false, true);
    }

    private static void run(boolean acknowledged, boolean cleanupFatal) throws Exception {
        run(acknowledged, cleanupFatal, false);
    }

    private static void run(boolean acknowledged, boolean cleanupFatal, boolean sameError) throws Exception {
        var delivery = new IllegalStateException("late delivery failure");
        Throwable shutdown = sameError
                ? delivery
                : cleanupFatal
                        ? new InternalError("later owned shutdown fatal")
                        : new IllegalStateException("later owned shutdown failure");
        var injected = new AtomicBoolean(true);
        var shutdownCalls = new AtomicInteger();
        var users = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void shutdown() {
                shutdownCalls.incrementAndGet();
                super.shutdown();
                if (injected.get()) ExceptionHelper.sneakyThrow(shutdown);
            }
        };
        var callers = Executors.newSingleThreadExecutor();
        var caller = new AtomicReference<Thread>();
        var callerInterrupted = new AtomicBoolean();
        var serializationEntered = new CountDownLatch(1);
        var ownerInterrupted = new CountDownLatch(1);
        var releaseSerialization = new CountDownLatch(1);
        var owner = new AtomicReference<Thread>();
        var ends = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var updates = new AtomicReference<List<OperationUpdate>>();
        DurableExecutionClient client;
        if (acknowledged) {
            client = mock(DurableExecutionClient.class);
            when(client.checkpoint(any(), any(), any())).thenAnswer(call -> {
                updates.set(List.copyOf(call.getArgument(2)));
                return CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("accepted-token")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .nextMarker("metadata")
                                .build())
                        .build();
            });
            when(client.getExecutionState(any(), any(), any())).thenThrow(delivery);
        } else {
            client = TestUtils.createMockClient();
        }
        var result = acknowledged ? "x".repeat(6 * 1024 * 1024 + 100) : "result";
        var serializer = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if (!acknowledged && result.equals(value)) {
                    serializationEntered.countDown();
                    boolean interrupted = false;
                    try {
                        while (true) {
                            try {
                                assertTrue(releaseSerialization.await(5, TimeUnit.SECONDS));
                                break;
                            } catch (InterruptedException observed) {
                                interrupted = true;
                                ownerInterrupted.countDown();
                            }
                        }
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                    throw delivery;
                }
                return super.serialize(value);
            }
        };
        var config = DurableConfig.builder()
                .withInvocationExecutorFactory(() -> users)
                .withCheckpointDelay(Duration.ZERO)
                .withDurableExecutionClient(client)
                .withSerDes(serializer)
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationStart(InvocationInfo info) {
                        owner.set(Thread.currentThread());
                    }

                    @Override
                    public void onInvocationEnd(InvocationEndInfo info) {
                        assertSame(owner.get(), Thread.currentThread());
                        ends.incrementAndGet();
                        end.set(info);
                    }
                })
                .build();
        try {
            var invocation = callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input(), runtime(), TypeToken.get(String.class), (value, context) -> result, config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            if (!acknowledged) {
                assertTrue(serializationEntered.await(5, TimeUnit.SECONDS));
                caller.get().interrupt();
                assertTrue(ownerInterrupted.await(5, TimeUnit.SECONDS));
                assertFalse(invocation.isDone(), "Caller must still wait for the actual serializer owner");
                releaseSerialization.countDown();
            }
            var observed = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                    .getCause();
            assertAcyclic(observed, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (cleanupFatal) {
                assertSame(shutdown, observed);
                assertEquals(1, countIdentity(observed, delivery));
            } else if (acknowledged) {
                assertSame(delivery, observed, "ACK must not erase the unrelated metadata failure");
            } else {
                assertTrue(
                        ExecutionManager.isLifecycleFailure(observed), "The caller's recorded cutoff remains primary");
                assertEquals(1, countIdentity(observed, delivery));
                assertEquals(1, countIdentity(observed, shutdown));
            }
            assertEquals(!acknowledged, callerInterrupted.get());
            assertEquals(1, ends.get());
            assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
            assertSame(delivery, end.get().executionError());
            assertEquals(1, shutdownCalls.get());
            if (acknowledged) {
                verify(client, times(1)).checkpoint(any(), any(), any());
                verify(client, times(1)).getExecutionState(any(), eq("accepted-token"), eq("metadata"));
                assertEquals(1, updates.get().size());
                assertEquals(OperationAction.SUCCEED, updates.get().get(0).action());
                assertEquals('"' + result + '"', updates.get().get(0).payload());
            }
        } finally {
            releaseSerialization.countDown();
            injected.set(false);
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static int countIdentity(Throwable node, Throwable expected) {
        if (node == null) return 0;
        int count = node == expected ? 1 : 0;
        count += countIdentity(node.getCause(), expected);
        for (var suppressed : node.getSuppressed()) count += countIdentity(suppressed, expected);
        return count;
    }

    private static void assertAcyclic(Throwable node, Set<Throwable> path) {
        if (node == null) return;
        assertTrue(path.add(node), "SDK aggregation must not create a cycle");
        assertAcyclic(node.getCause(), path);
        for (var suppressed : node.getSuppressed()) assertAcyclic(suppressed, path);
        path.remove(node);
    }

    private static Context runtime() {
        var context = mock(Context.class);
        when(context.getRemainingTimeInMillis()).thenReturn(10_000);
        when(context.getAwsRequestId()).thenReturn("completion-adjacent-request");
        return context;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/adjacent/exec",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("exec")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
    }
}
