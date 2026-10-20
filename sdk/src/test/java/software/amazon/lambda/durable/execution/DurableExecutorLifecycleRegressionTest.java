// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static software.amazon.lambda.durable.TypeToken.get;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;

@Timeout(30)
class DurableExecutorLifecycleRegressionTest {
    @Test
    void revocationMustWaitForRootHandlerBeforeReturning() throws Exception {
        try (var scenario = new RootHandlerScenario(false)) {
            scenario.awaitFirstCheckpoint();
            assertThrows(
                    TimeoutException.class,
                    () -> scenario.invocation.get(500, TimeUnit.MILLISECONDS),
                    "Invocation must not return while its root handler is still running");

            scenario.releaseRoot.countDown();
            scenario.invocation.get(5, TimeUnit.SECONDS);
            assertEquals(0, scenario.rootFinished.getCount());
        }
    }

    @Test
    void laterStepMustUnwindAfterTokenWithdrawal() throws Exception {
        try (var scenario = new RootHandlerScenario(true)) {
            scenario.awaitFirstCheckpoint();
            scenario.releaseRoot.countDown();
            assertTrue(
                    scenario.rootFinished.await(5, TimeUnit.SECONDS),
                    "Root remains blocked on a later operation after token withdrawal");
            scenario.invocation.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failureSerializationStillFiresInvocationEnd() {
        var ended = new AtomicInteger();
        var serializationFailure = new IllegalStateException("Cannot serialize failure");
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .withSerDes(failingErrorSerializer(serializationFailure))
                .withPlugins(ignoredInfo -> recordingPlugin(ended))
                .build();

        var actual = assertThrows(
                IllegalStateException.class,
                () -> execute(config, (value, ctx) -> {
                    throw new IllegalArgumentException("handler failed");
                }));

        assertSame(serializationFailure, actual);
        assertEquals(1, ended.get(), "Error serialization must not suppress the invocation-end hook");
    }

    @Test
    void interruptedDrainFinishesContinuationAndPeerBeforeInvocationEnd() throws Exception {
        var releaseStep = new CountDownLatch(1);
        var handlerReturning = new CountDownLatch(1);
        var continuationEntered = new CountDownLatch(1);
        var releaseContinuation = new CountDownLatch(1);
        var continuationWorkers = Executors.newSingleThreadExecutor();
        var interruptedAtEnd = new AtomicBoolean();
        var ended = new CountDownLatch(1);
        var endInfo = new AtomicReference<InvocationEndInfo>();
        var rootThread = new AtomicReference<Thread>();
        var userExecutor = Executors.newFixedThreadPool(2);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(TestUtils.createMockClient())
                    .withExecutorService(userExecutor)
                    .withPlugins(ignoredInfo -> new DurableExecutionPlugin() {
                        @Override
                        public void onInvocationEnd(InvocationEndInfo info) {
                            endInfo.set(info);
                            interruptedAtEnd.set(Thread.currentThread().isInterrupted());
                            ended.countDown();
                        }
                    })
                    .build();

            var invocation = caller.submit(() -> execute(config, (value, context) -> {
                // Never awaited, so the root reaches drainOperations() while this step is still running.
                context.stepAsync("blocked", String.class, step -> {
                    await(releaseStep);
                    return "late";
                });
                ((DurableContextImpl) context)
                        .getExecutionManager()
                        .runCheckpointContinuation(
                                () -> {
                                    continuationEntered.countDown();
                                    await(releaseContinuation);
                                },
                                continuationWorkers);
                await(continuationEntered);
                rootThread.set(Thread.currentThread());
                handlerReturning.countDown();
                return "root-result";
            }));

            await(handlerReturning);
            // The interrupt flag is sticky, so the drain observes it whether it lands before or during the wait.
            rootThread.get().interrupt();

            assertThrows(TimeoutException.class, () -> invocation.get(150, TimeUnit.MILLISECONDS));
            assertEquals(1L, ended.getCount(), "An interrupt must not let End overtake the held continuation");
            releaseContinuation.countDown();
            assertThrows(TimeoutException.class, () -> invocation.get(150, TimeUnit.MILLISECONDS));
            assertEquals(1L, ended.getCount(), "After the continuation, End must still join the held peer");
            releaseStep.countDown();
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            assertEquals(InvocationStatus.RETRYING, endInfo.get().invocationStatus());
            assertInstanceOf(IllegalStateException.class, endInfo.get().executionError());
            assertInstanceOf(
                    InterruptedException.class, endInfo.get().executionError().getCause());
            assertTrue(interruptedAtEnd.get(), "Interrupt status is restored only after required drain work");
            var failure = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            releaseContinuation.countDown();
            releaseStep.countDown();
            continuationWorkers.shutdownNow();
            assertTrue(continuationWorkers.awaitTermination(5, TimeUnit.SECONDS));
            caller.shutdownNow();
            userExecutor.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS), "Invocation executor did not terminate");
            assertTrue(userExecutor.awaitTermination(5, TimeUnit.SECONDS), "User executor did not terminate");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledExecutorTaskRetainsActualExitBoundary(boolean running) throws Exception {
        var submitted = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var manager = new AtomicReference<ExecutionManager>();
        var bodies = new AtomicInteger();
        var users = Executors.newFixedThreadPool(running ? 2 : 1);
        var callers = Executors.newSingleThreadExecutor();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withExecutorService(users)
                .withCheckpointDelay(Duration.ZERO)
                .withPlugins(info -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationEnd(InvocationEndInfo end) {
                        ended.countDown();
                    }
                })
                .build();
        try {
            var invocation = callers.submit(() -> execute(config, (value, context) -> {
                manager.set(((DurableContextImpl) context).getExecutionManager());
                context.stepAsync("held", String.class, step -> {
                    bodies.incrementAndGet();
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // This test owner deliberately keeps running after cancellation until explicitly released.
                        }
                    }
                    return "stored";
                });
                if (running) await(entered);
                submitted.countDown();
                return "root-result";
            }));
            await(submitted);
            var task = manager.get().getActiveExecutorTasks().stream()
                    .filter(handle -> handle.role() == ExecutorTaskHandle.Role.STEP)
                    .findFirst()
                    .orElseThrow();
            assertTrue(task.cancel(true));
            if (running) {
                assertThrows(TimeoutException.class, () -> invocation.get(100, TimeUnit.MILLISECONDS));
                assertEquals(1, ended.getCount(), "Logical cancellation must not let End overtake actual task exit");
                release.countDown();
                assertEquals(
                        ExecutionStatus.SUCCEEDED,
                        invocation.get(5, TimeUnit.SECONDS).status());
            } else {
                var failure = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            }
            assertEquals(running ? 1 : 0, bodies.get());
            assertEquals(0, ended.getCount());
            assertTrue(task.exit().isDone());
        } finally {
            release.countDown();
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nestedWorkAdmittedWhileParentDrainsMustFinishBeforeEnd() throws Exception {
        var parentReady = new CountDownLatch(1);
        var releaseParent = new CountDownLatch(1);
        var nestedEntered = new CountDownLatch(1);
        var releaseNested = new CountDownLatch(1);
        var nestedExited = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var rootThread = new AtomicReference<Thread>();
        var workers = Executors.newCachedThreadPool();
        var callers = Executors.newSingleThreadExecutor();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ZERO)
                .withPlugins(info -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationEnd(InvocationEndInfo end) {
                        ended.countDown();
                    }
                })
                .build();
        var execution = Operation.builder()
                .id("exec")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/lifecycle/exec",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(execution).build());
        try {
            var invocation = callers.submit(() -> DurableExecutor.execute(
                    input,
                    null,
                    get(String.class),
                    (value, context) -> {
                        rootThread.set(Thread.currentThread());
                        context.runInChildContextAsync("parent", String.class, child -> {
                            child.step("first", String.class, step -> "one");
                            child.step("second", String.class, step -> "two");
                            child.step("third", String.class, step -> "three");
                            child.step("fourth", String.class, step -> "four");
                            parentReady.countDown();
                            await(releaseParent);
                            child.stepAsync("fifth", String.class, step -> {
                                nestedEntered.countDown();
                                try {
                                    await(releaseNested);
                                    return "late";
                                } finally {
                                    nestedExited.countDown();
                                }
                            });
                            await(nestedEntered);
                            return "parent-result";
                        });
                        await(parentReady);
                        return "root-result";
                    },
                    config));
            await(parentReady);
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline
                    && Arrays.stream(rootThread.get().getStackTrace())
                            .noneMatch(frame -> frame.getMethodName().equals("awaitUserHandler"))) Thread.onSpinWait();
            assertTrue(
                    Arrays.stream(rootThread.get().getStackTrace())
                            .anyMatch(frame -> frame.getMethodName().equals("awaitUserHandler")),
                    "Root must enter actual drain");
            releaseParent.countDown();
            await(nestedEntered);
            assertFalse(
                    ended.await(300, TimeUnit.MILLISECONDS), "End overtook nested work admitted by draining parent");
            assertEquals(1, nestedExited.getCount());
            releaseNested.countDown();
            invocation.get(5, TimeUnit.SECONDS);
            assertEquals(0, ended.getCount());
        } finally {
            releaseParent.countDown();
            releaseNested.countDown();
            workers.shutdownNow();
            callers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static JacksonSerDes failingErrorSerializer(RuntimeException failure) {
        return new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if (value instanceof Throwable) {
                    throw failure;
                }
                return super.serialize(value);
            }
        };
    }

    @Test
    void interruptedDrainPreservesLateStepResultForReplay() throws Exception {
        var recorded = new ConcurrentHashMap<String, Operation>();
        var delegate = TestUtils.createMockClient();
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(call -> {
            var response = delegate.checkpoint(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            response.newExecutionState().operations().forEach(operation -> recorded.put(operation.id(), operation));
            return response;
        });
        var users = Executors.newFixedThreadPool(2);
        var caller = Executors.newSingleThreadExecutor();
        var stepEntered = new CountDownLatch(1);
        var handlerReturned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var root = new AtomicReference<Thread>();
        var bodies = new AtomicInteger();
        var config = DurableConfig.builder()
                .withExecutorService(users)
                .withDurableExecutionClient(client)
                .build();
        BiFunction<String, DurableContext, String> handler = (value, context) -> {
            context.stepAsync("late-result", String.class, step -> {
                bodies.incrementAndGet();
                stepEntered.countDown();
                await(release);
                return "stored";
            });
            root.set(Thread.currentThread());
            handlerReturned.countDown();
            return "root-result";
        };
        try {
            var first = caller.submit(() -> execute(config, handler));
            assertTrue(stepEntered.await(5, TimeUnit.SECONDS));
            assertTrue(handlerReturned.await(5, TimeUnit.SECONDS));
            root.get().interrupt();
            assertThrows(TimeoutException.class, () -> first.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertInstanceOf(
                    IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS))
                            .getCause());
            var step = recorded.values().stream()
                    .filter(operation -> "late-result".equals(operation.name()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(OperationStatus.SUCCEEDED, step.status());
            var replay = execute(config, handler, List.copyOf(recorded.values()));
            assertEquals(ExecutionStatus.SUCCEEDED, replay.status());
            assertEquals(1, bodies.get(), "The acknowledged late result must replay without repeating its side effect");
        } finally {
            release.countDown();
            caller.shutdownNow();
            users.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(users.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static DurableExecutionPlugin recordingPlugin(AtomicInteger ended) {
        return new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                ended.incrementAndGet();
            }
        };
    }

    private static DurableExecutionOutput execute(
            DurableConfig config, BiFunction<String, DurableContext, String> handler) {
        return execute(config, handler, List.of());
    }

    private static DurableExecutionOutput execute(
            DurableConfig config,
            BiFunction<String, DurableContext, String> handler,
            List<Operation> replayOperations) {
        var execution = Operation.builder()
                .id("exec")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        var operations = new ArrayList<>(replayOperations);
        operations.add(execution);
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/lifecycle/exec",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operations).build());
        return DurableExecutor.execute(input, null, get(String.class), handler, config);
    }

    private static class RootHandlerScenario implements AutoCloseable {
        private final CountDownLatch rootPaused = new CountDownLatch(1);
        private final CountDownLatch releaseRoot = new CountDownLatch(1);
        private final CountDownLatch continuationReached = new CountDownLatch(1);
        private final CountDownLatch rootFinished = new CountDownLatch(1);
        private final AtomicReference<BaseDurableOperation> firstOperation = new AtomicReference<>();
        private final AtomicReference<BaseDurableOperation> lateOperation = new AtomicReference<>();
        private final ExecutorService userExecutor = Executors.newFixedThreadPool(2);
        private final ExecutorService caller = Executors.newSingleThreadExecutor();
        private final Future<DurableExecutionOutput> invocation;

        private RootHandlerScenario(boolean startLaterStep) {
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(revokingClient())
                    .withExecutorService(userExecutor)
                    .build();
            invocation = caller.submit(() -> execute(config, (value, context) -> runHandler(context, startLaterStep)));
        }

        private DurableExecutionClient revokingClient() {
            var delegate = TestUtils.createMockClient();
            var client = mock(DurableExecutionClient.class);
            var checkpoints = new AtomicInteger();
            when(client.checkpoint(any(), any(), any())).thenAnswer(call -> {
                await(rootPaused);
                var response = delegate.checkpoint(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                // Preserve accepted operation state so the same test can run against the pre-change implementation.
                return checkpoints.incrementAndGet() == 1
                        ? response.toBuilder().checkpointToken(null).build()
                        : response;
            });
            return client;
        }

        private String runHandler(DurableContext context, boolean startLaterStep) {
            try {
                firstOperation.set((BaseDurableOperation) context.stepAsync("async", String.class, step -> "done"));
                rootPaused.countDown();
                // Hold the root between durable calls to make token withdrawal during orchestration reproducible.
                await(releaseRoot);
                if (startLaterStep) {
                    var next = context.stepAsync("next", String.class, step -> "next");
                    lateOperation.set((BaseDurableOperation) next);
                    continuationReached.countDown();
                    return next.get();
                }
                return "root-result";
            } finally {
                // Immediate suspension from stepAsync is also a valid way to unwind the root.
                continuationReached.countDown();
                rootFinished.countDown();
            }
        }

        private void awaitFirstCheckpoint() throws Exception {
            await(rootPaused);
            firstOperation.get().getRunningUserHandler().get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            releaseRoot.countDown();
            try {
                await(continuationReached);
                var operation = lateOperation.get();
                if (operation != null) {
                    // Cleanup only: release the stranded future after the assertion so a failing test cannot leak it.
                    operation.getCompletionFuture().completeExceptionally(new SuspendExecutionException());
                }
                assertTrue(rootFinished.await(5, TimeUnit.SECONDS), "Root handler did not exit during cleanup");
            } finally {
                caller.shutdownNow();
                userExecutor.shutdownNow();
                assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS), "Invocation executor did not terminate");
                assertTrue(userExecutor.awaitTermination(5, TimeUnit.SECONDS), "User executor did not terminate");
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for test coordination");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
