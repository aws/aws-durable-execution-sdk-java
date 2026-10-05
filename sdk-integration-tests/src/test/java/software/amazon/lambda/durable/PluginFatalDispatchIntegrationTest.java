// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

@Timeout(10)
class PluginFatalDispatchIntegrationTest {
    @SuppressWarnings("removal")
    static Stream<Arguments> failures() {
        return Stream.of(false, true)
                .flatMap(wrapped -> Stream.of(new InternalError("plugin start"), new ThreadDeath())
                        .map(fatal -> Arguments.of(wrapped, fatal)));
    }

    @SuppressWarnings("removal")
    static Stream<Arguments> asyncCases() {
        return Stream.of(false, true)
                .flatMap(queued -> failures().map(args -> Arguments.of(queued, args.get()[0], args.get()[1])));
    }

    @ParameterizedTest
    @MethodSource("asyncCases")
    void fatalPreventsNewAndQueuedAtLeastOnceBodies(boolean queued, boolean wrapped, Error fatal) throws Exception {
        var releaseQueued = new CountDownLatch(1);
        var queuedFinished = new CountDownLatch(1);
        var fatalObserved = new CountDownLatch(1);
        var dispatchFinished = new CountDownLatch(1);
        var bodies = new AtomicInteger();
        var lateStarts = new AtomicInteger();
        var endCalls = new AtomicInteger();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        var dispatchFailure = new AtomicReference<Throwable>();
        var updates = new CopyOnWriteArrayList<OperationUpdate>();
        var workers =
                new ThreadPoolExecutor(
                        0,
                        Integer.MAX_VALUE,
                        60,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        task -> daemon(task, "fatal-dispatch-worker")) {
                    private final AtomicInteger submissions = new AtomicInteger();

                    public void execute(Runnable task) {
                        // The root is first and the held step is second. Leave the failing third task free to run.
                        if (queued && submissions.incrementAndGet() == 2) {
                            super.execute(() -> {
                                try {
                                    await(releaseQueued);
                                    task.run();
                                } finally {
                                    queuedFinished.countDown();
                                }
                            });
                        } else super.execute(task);
                    }
                };
        DurableExecutionPluginFactory plugin = ignored -> new DurableExecutionPlugin() {
            public void onUserFunctionStart(UserFunctionStartInfo info) {
                if ("late".equals(info.name())) lateStarts.incrementAndGet();
                if ("trigger".equals(info.name())) fail(wrapped, fatal);
            }

            public void onInvocationEnd(InvocationEndInfo info) {
                endCalls.incrementAndGet();
                endInfo.set(info);
                fatalObserved.countDown();
                releaseQueued.countDown();
                await(queued ? queuedFinished : dispatchFinished);
            }
        };
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ZERO)
                .withDurableExecutionClient(new LocalMemoryExecutionClient() {
                    public CheckpointDurableExecutionResponse checkpoint(
                            String arn, String token, List<OperationUpdate> batch) {
                        updates.addAll(batch);
                        return super.checkpoint(arn, token, batch);
                    }
                })
                .withPlugins(plugin)
                .build();
        try {
            assertSame(
                    fatal,
                    assertThrows(
                            Error.class,
                            () -> DurableExecutor.execute(
                                    input(),
                                    null,
                                    TypeToken.get(String.class),
                                    (value, context) -> {
                                        if (queued)
                                            context.stepAsync("late", String.class, step -> {
                                                bodies.incrementAndGet();
                                                return "unexpected";
                                            });
                                        context.stepAsync("trigger", String.class, step -> "unreachable");
                                        await(fatalObserved);
                                        if (!queued) {
                                            try {
                                                context.stepAsync("late", String.class, step -> {
                                                    bodies.incrementAndGet();
                                                    return "unexpected";
                                                });
                                            } catch (Error error) {
                                                dispatchFailure.set(error);
                                            } finally {
                                                dispatchFinished.countDown();
                                            }
                                        }
                                        return "done";
                                    },
                                    config)));
            assertEquals(0, bodies.get(), "no new user body may run after the fatal was recorded");
            assertEquals(0, lateStarts.get(), "a queued handler must stop before opening plugin attempt scopes");
            assertTrue(updates.stream().noneMatch(update -> "late".equals(update.name())));
            if (!queued) assertSame(fatal, dispatchFailure.get());
            assertEquals(1, endCalls.get());
            assertEquals(InvocationStatus.RETRYING, endInfo.get().invocationStatus());
            assertSame(fatal, endInfo.get().executionError());
        } finally {
            releaseQueued.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @MethodSource("failures")
    void inlineFatalEscapesTheOperationCallBeforeMoreHandlerCodeRuns(boolean wrapped, Error fatal) {
        var continued = new AtomicBoolean();
        var bodyCalled = new AtomicBoolean();
        var endCalls = new AtomicInteger();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        DurableExecutionPluginFactory plugin = ignored -> new DurableExecutionPlugin() {
            public void onUserFunctionStart(UserFunctionStartInfo info) {
                fail(wrapped, fatal);
            }

            public void onInvocationEnd(InvocationEndInfo info) {
                endCalls.incrementAndGet();
                endInfo.set(info);
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(new LocalMemoryExecutionClient())
                .withExecutorService(new DirectExecutor())
                .withPlugins(plugin)
                .build();
        assertSame(
                fatal,
                assertThrows(
                        Error.class,
                        () -> DurableExecutor.execute(
                                input(),
                                null,
                                TypeToken.get(String.class),
                                (value, context) -> {
                                    context.stepAsync("trigger", String.class, step -> {
                                        bodyCalled.set(true);
                                        return "unexpected";
                                    });
                                    continued.set(true);
                                    return "done";
                                },
                                config)));
        assertFalse(continued.get(), "an inline fatal must leave the operation call exceptionally");
        assertFalse(bodyCalled.get());
        assertEquals(1, endCalls.get());
        assertEquals(InvocationStatus.RETRYING, endInfo.get().invocationStatus());
        assertSame(fatal, endInfo.get().executionError());
    }

    private static void fail(boolean wrapped, Error fatal) {
        if (wrapped) throw new CompletionException(new ExecutionException(fatal));
        throw fatal;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "worker coordination timed out");
        } catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((owner, failure) -> {});
        return thread;
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    private static final class DirectExecutor extends AbstractExecutorService {
        public void execute(Runnable task) {
            task.run();
        }

        public void shutdown() {}

        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
