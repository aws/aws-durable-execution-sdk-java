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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class OperationWorkerFailureRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedOperationSubmissionSettlesShutdown(boolean direct) throws Exception {
        ExecutorService workers = direct
                ? new DirectExecutor(true)
                : new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        task -> daemon(task, "bounded-owner"),
                        new ThreadPoolExecutor.AbortPolicy());
        var callers = Executors.newSingleThreadExecutor(task -> daemon(task, "rejection-caller"));
        var bodyCalled = new AtomicBoolean();
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withDurableExecutionClient(new LocalMemoryExecutionClient())
                    .build();
            var result = callers.submit(() -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, context) -> context.step("rejected", String.class, step -> {
                                bodyCalled.set(true);
                                return "unexpected";
                            }),
                            config))
                    .get(2, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.FAILED, result.status());
            assertEquals(
                    RejectedExecutionException.class.getName(), result.error().errorType());
            assertFalse(bodyCalled.get());
        } finally {
            workers.shutdownNow();
            callers.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handledRejectionDoesNotLeaveAPhantomActiveOperation(boolean direct) throws Exception {
        ExecutorService workers = direct
                ? new DirectExecutor(true)
                : new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        task -> daemon(task, "bounded-owner"),
                        new ThreadPoolExecutor.AbortPolicy());
        var callers = Executors.newSingleThreadExecutor(task -> daemon(task, "rejection-caller"));
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withDurableExecutionClient(new LocalMemoryExecutionClient())
                    .build();
            var result = callers.submit(() -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                assertThrows(
                                        RejectedExecutionException.class,
                                        () -> context.step("rejected", String.class, step -> "unexpected"));
                                context.wait("pause", Duration.ofSeconds(1));
                                return "done";
                            },
                            config))
                    .get(2, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.PENDING, result.status(), "rejected work is not an active handler");
        } finally {
            workers.shutdownNow();
            callers.shutdownNow();
        }
    }

    @SuppressWarnings("removal")
    static Stream<Arguments> hookCases() {
        return Stream.of(false, true)
                .flatMap(direct -> Stream.of(false, true)
                        .flatMap(end -> Stream.of(false, true)
                                .flatMap(wrapped -> Stream.of(new InternalError("plugin fatal"), new ThreadDeath())
                                        .map(fatal -> Arguments.of(direct, end, wrapped, fatal)))));
    }

    @ParameterizedTest
    @MethodSource("hookCases")
    void waitHookFatalBypassesSerializationAndEscapesItsOwner(boolean direct, boolean end, boolean wrapped, Error fatal)
            throws Exception {
        runWaitFatal(direct, end, wrapped, fatal, Duration.ZERO);
    }

    @SuppressWarnings("removal")
    @Test
    void waitHookFatalDoesNotWaitForTheFailureCheckpointDelay() throws Exception {
        runWaitFatal(false, false, false, new ThreadDeath(), Duration.ofSeconds(3));
    }

    private static void runWaitFatal(boolean direct, boolean atEnd, boolean wrapped, Error fatal, Duration delay)
            throws Exception {
        var hookThread = new AtomicReference<Thread>();
        var callerThread = new AtomicReference<Thread>();
        var escaped = new CountDownLatch(1);
        var serializedFatal = new AtomicInteger();
        var ends = new AtomicInteger();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        var updates = new CopyOnWriteArrayList<OperationUpdate>();
        ExecutorService workers = direct
                ? new DirectExecutor(false)
                : Executors.newCachedThreadPool(task -> {
                    var thread = daemon(task, "wait-owner");
                    thread.setUncaughtExceptionHandler((owner, error) -> {
                        if (owner == hookThread.get() && error == fatal) escaped.countDown();
                    });
                    return thread;
                });
        var callers = Executors.newSingleThreadExecutor(task -> daemon(task, "wait-caller"));
        var client = new LocalMemoryExecutionClient() {
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> batch) {
                updates.addAll(batch);
                return super.checkpoint(arn, token, batch);
            }
        };
        var serdes = new JacksonSerDes() {
            public String serialize(Object value) {
                if (value == fatal) serializedFatal.incrementAndGet();
                return super.serialize(value);
            }
        };
        DurableExecutionPluginFactory factory = ignored -> new DurableExecutionPlugin() {
            private void fail() {
                hookThread.set(Thread.currentThread());
                if (wrapped) throw new CompletionException(new ExecutionException(fatal));
                throw fatal;
            }

            public void onUserFunctionStart(UserFunctionStartInfo info) {
                if (!atEnd) fail();
            }

            public void onUserFunctionEnd(UserFunctionEndInfo info) {
                if (atEnd) fail();
            }

            public void onInvocationEnd(InvocationEndInfo info) {
                ends.incrementAndGet();
                endInfo.set(info);
            }
        };
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withDurableExecutionClient(client)
                    .withSerDes(serdes)
                    .withCheckpointDelay(delay)
                    .withPlugins(factory)
                    .build();
            var response = callers.submit(() -> {
                callerThread.set(Thread.currentThread());
                return DurableExecutor.execute(
                        input(),
                        null,
                        TypeToken.get(String.class),
                        (value, context) -> context.waitForCondition(
                                "work",
                                String.class,
                                (state, step) -> WaitForConditionResult.stopPolling("done"),
                                WaitForConditionConfig.<String>builder().build()),
                        config);
            });
            var error = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
            assertSame(fatal, error.getCause());
            assertEquals(0, serializedFatal.get(), "a plugin fatal must not invoke user exception serialization");
            assertTrue(updates.stream().noneMatch(update -> update.action() == OperationAction.FAIL));
            assertEquals(1, ends.get());
            assertEquals(InvocationStatus.RETRYING, endInfo.get().invocationStatus());
            assertSame(fatal, endInfo.get().executionError());
            if (direct) assertSame(callerThread.get(), hookThread.get());
            else assertTrue(escaped.await(2, TimeUnit.SECONDS), "the identical fatal must escape the hook worker");
        } finally {
            workers.shutdownNow();
            callers.shutdownNow();
        }
    }

    @Test
    void legacyWaitBodyFatalStillUsesItsExistingFailurePath() {
        var fatal = new InternalError("user condition body");
        var serialized = new AtomicInteger();
        var updates = new CopyOnWriteArrayList<OperationUpdate>();
        var client = new LocalMemoryExecutionClient() {
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> batch) {
                updates.addAll(batch);
                return super.checkpoint(arn, token, batch);
            }
        };
        var serdes = new JacksonSerDes() {
            public String serialize(Object value) {
                if (value == fatal) serialized.incrementAndGet();
                return super.serialize(value);
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withSerDes(serdes)
                .withCheckpointDelay(Duration.ZERO)
                .build();
        var actual = assertThrows(
                InternalError.class,
                () -> DurableExecutor.execute(
                        input(),
                        null,
                        TypeToken.get(String.class),
                        (value, context) -> context.waitForCondition(
                                "work",
                                String.class,
                                (state, step) -> {
                                    throw fatal;
                                },
                                WaitForConditionConfig.<String>builder().build()),
                        config));
        assertNotSame(fatal, actual);
        assertEquals(1, serialized.get());
        assertTrue(updates.stream().anyMatch(update -> update.action() == OperationAction.FAIL));
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
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

    private static class DirectExecutor extends AbstractExecutorService {
        private final boolean rejectOperations;
        private int submitted;

        DirectExecutor(boolean rejectOperations) {
            this.rejectOperations = rejectOperations;
        }

        public void execute(Runnable task) {
            if (rejectOperations && submitted++ > 0) throw new RejectedExecutionException("operation rejected");
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
