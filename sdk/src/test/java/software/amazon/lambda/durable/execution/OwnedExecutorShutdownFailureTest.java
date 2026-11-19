// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;

@Timeout(15)
class OwnedExecutorShutdownFailureTest {
    enum ShutdownStage {
        SHUTDOWN,
        INTERRUPTED_FALLBACK,
        STATE_QUERY
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({
        "false, SHUTDOWN, false", "true, SHUTDOWN, false",
        "false, INTERRUPTED_FALLBACK, false", "true, INTERRUPTED_FALLBACK, false",
        "false, STATE_QUERY, false", "true, STATE_QUERY, false",
        "false, SHUTDOWN, true", "true, SHUTDOWN, true",
        "false, INTERRUPTED_FALLBACK, true", "true, INTERRUPTED_FALLBACK, true",
        "false, STATE_QUERY, true", "true, STATE_QUERY, true"
    })
    void serializationFatalRemainsPrimaryWhenOwnedShutdownIntroducesAnotherFatal(
            boolean deathFirst, ShutdownStage stage, boolean inline) throws Exception {
        Error first = deathFirst ? new ThreadDeath() : new InternalError("first serializer fatal");
        Error second = deathFirst ? new InternalError("later owned shutdown fatal") : new ThreadDeath();
        var workerFailure = new AtomicReference<Throwable>();
        var escaped = new CountDownLatch(1);
        var ends = new AtomicInteger();
        var shutdownCalls = new AtomicInteger();
        var injectFailure = new AtomicBoolean(true);
        var callerInterrupted = new AtomicBoolean();
        var owner = new AtomicReference<Thread>();
        var users =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), action -> {
                    var thread = new Thread(action, "owned-fatal-owner");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        workerFailure.set(failure);
                        escaped.countDown();
                    });
                    return thread;
                }) {
                    @Override
                    public void execute(Runnable command) {
                        if (inline) command.run();
                        else super.execute(command);
                    }

                    @Override
                    public void shutdown() {
                        shutdownCalls.incrementAndGet();
                        super.shutdown();
                        if (injectFailure.get() && stage == ShutdownStage.SHUTDOWN) throw second;
                    }

                    @Override
                    public List<Runnable> shutdownNow() {
                        var queued = super.shutdownNow();
                        if (injectFailure.get() && stage == ShutdownStage.INTERRUPTED_FALLBACK) throw second;
                        return queued;
                    }

                    @Override
                    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                        if (injectFailure.get() && stage == ShutdownStage.INTERRUPTED_FALLBACK) {
                            Thread.currentThread().interrupt();
                            new CountDownLatch(1)
                                    .await(); // Observe the interruption even if an inline pool terminated.
                        }
                        return super.awaitTermination(timeout, unit);
                    }

                    @Override
                    public boolean isShutdown() {
                        var stopped = super.isShutdown();
                        if (injectFailure.get() && stage == ShutdownStage.STATE_QUERY && stopped) throw second;
                        return stopped;
                    }
                };
        var callers = Executors.newSingleThreadExecutor();
        var serializer = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if ("result".equals(value)) throw first;
                return super.serialize(value);
            }
        };
        try {
            var config = DurableConfig.builder()
                    .withInvocationExecutorFactory(() -> users)
                    .withDurableExecutionClient(TestUtils.createMockClient())
                    .withSerDes(serializer)
                    .withPlugins(ignoredInvocation -> new DurableExecutionPlugin() {
                        @Override
                        public void onInvocationStart(InvocationInfo info) {
                            owner.set(Thread.currentThread());
                        }

                        @Override
                        public void onInvocationEnd(InvocationEndInfo info) {
                            assertSame(owner.get(), Thread.currentThread());
                            assertEquals(InvocationStatus.RETRYING, info.invocationStatus());
                            assertSame(first, info.executionError());
                            ends.incrementAndGet();
                        }
                    })
                    .build();
            var invocation = callers.submit(() -> {
                try {
                    return DurableExecutor.execute(
                            input(),
                            runtime(10_000),
                            TypeToken.get(String.class),
                            (value, context) -> "result",
                            config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            assertSame(
                    first,
                    assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                            .getCause());
            if (inline) {
                assertNull(workerFailure.get(), "The inline fatal belongs to the invocation caller");
            } else {
                assertTrue(escaped.await(3, TimeUnit.SECONDS));
                assertSame(first, workerFailure.get());
            }
            assertEquals(1, ends.get());
            assertEquals(1, shutdownCalls.get(), "Implicit close must not retry an already-finalized invocation");
            assertEquals(stage == ShutdownStage.INTERRUPTED_FALLBACK, callerInterrupted.get());
            assertTrue(Arrays.asList(first.getSuppressed()).contains(second));
        } finally {
            injectFailure.set(false);
            callers.shutdownNow();
            users.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void ownedShutdownFatalOutranksAcknowledgedSuccessAndCallerInterruption() throws Exception {
        var fatal = new InternalError("owned shutdown fatal after ACK");
        var injectFailure = new AtomicBoolean(true);
        var shutdownCalls = new AtomicInteger();
        var users = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void shutdown() {
                shutdownCalls.incrementAndGet();
                super.shutdown();
                if (injectFailure.get()) throw fatal;
            }
        };
        var callers = Executors.newSingleThreadExecutor();
        var caller = new AtomicReference<Thread>();
        var callerInterrupted = new AtomicBoolean();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        var endEntered = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(1);
        var client = TestUtils.createMockClient();
        var result = "x".repeat(6 * 1024 * 1024 + 100); // Force the explicit EXECUTION SUCCEED checkpoint.
        var config = DurableConfig.builder()
                .withInvocationExecutorFactory(() -> users)
                .withDurableExecutionClient(client)
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationEnd(InvocationEndInfo info) {
                        endInfo.set(info);
                        endEntered.countDown();
                        try {
                            assertTrue(releaseEnd.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })
                .build();
        try {
            var invocation = callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input(), runtime(10_000), TypeToken.get(String.class), (value, context) -> result, config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            assertTrue(endEntered.await(5, TimeUnit.SECONDS));
            assertEquals(InvocationStatus.SUCCEEDED, endInfo.get().invocationStatus());
            assertNull(endInfo.get().executionError());
            caller.get().interrupt();
            assertSame(
                    fatal,
                    assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertTrue(callerInterrupted.get());
            assertEquals(1, shutdownCalls.get());
            verify(client, times(1))
                    .checkpoint(
                            any(),
                            any(),
                            argThat(updates -> updates.size() == 1
                                    && updates.get(0).type() == OperationType.EXECUTION
                                    && updates.get(0).action() == OperationAction.SUCCEED
                                    && updates.get(0).payload().equals('"' + result + '"')));
        } finally {
            releaseEnd.countDown();
            injectFailure.set(false);
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static Context runtime(int millis) {
        var context = mock(Context.class);
        when(context.getRemainingTimeInMillis()).thenReturn(millis);
        when(context.getAwsRequestId()).thenReturn("independent-request");
        return context;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/independent/exec",
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
