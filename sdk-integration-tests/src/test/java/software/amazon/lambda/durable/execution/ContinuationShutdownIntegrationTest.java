// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.BaseContext;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class ContinuationShutdownIntegrationTest {
    private ControlledManager initialManager;
    private BaseContext previousContext;
    private ThreadContext previousThreadContext;

    private void rememberContext(ControlledManager manager) {
        if (initialManager != null) return;
        initialManager = manager;
        previousContext = BaseContext.getCurrentContext();
        previousThreadContext = manager.getCurrentThreadContext();
    }

    @AfterEach
    void restoreCallerContext() {
        if (initialManager == null) return;
        BaseContextImpl.setCurrentContext(previousContext);
        initialManager.setCurrentThreadContext(previousThreadContext);
    }

    @Test
    void runningPredicateFinishesItsCheckpointAndReplaysWithoutRepeatingWork() throws Exception {
        var closed = new AtomicBoolean();
        var late = new AtomicInteger();
        var client = readyClient(closed, late);
        var queue = new LinkedBlockingQueue<Runnable>();
        var workers = Executors.newCachedThreadPool();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var setup = setup(client, workers, queue, closed);
        try {
            setup.context.waitForConditionAsync(
                    "condition",
                    Integer.class,
                    (state, step) -> {
                        calls.incrementAndGet();
                        if (state == 1) return WaitForConditionResult.continuePolling(2);
                        started.countDown();
                        await(release);
                        return WaitForConditionResult.stopPolling(state);
                    },
                    waitConfig());
            var task = queue.poll(3, TimeUnit.SECONDS);
            assertNotNull(task);
            CompletableFuture.runAsync(task).get(3, TimeUnit.SECONDS);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var closing = CompletableFuture.runAsync(setup.manager::close);
            assertTrue(setup.manager.closeEntered.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closing.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            closing.get(3, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
            assertEquals(
                    OperationStatus.SUCCEEDED,
                    client.getOperationByName("condition").status());
            var checkpoints = client.getOperationUpdates().size();
            var replay = setup(client, workers, new LinkedBlockingQueue<>(), new AtomicBoolean());
            try {
                assertEquals(
                        2,
                        replay.context.waitForCondition(
                                "condition",
                                Integer.class,
                                (state, step) -> {
                                    calls.incrementAndGet();
                                    throw new AssertionError("A completed predicate must not run on replay");
                                },
                                waitConfig()));
                assertEquals(2, calls.get());
                assertEquals(checkpoints, client.getOperationUpdates().size());
                assertEquals(0, late.get());
            } finally {
                replay.manager.close();
            }
            setup.manager
                    .runCheckpointContinuation(() -> fail("New work must be rejected after close"))
                    .get(3, TimeUnit.SECONDS);
            assertTrue(queue.isEmpty());
        } finally {
            release.countDown();
            setup.manager.close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void earlyParallelCompletionStopsOnlyItsQueuedConditionAndUnblocksTheWaitingBranch() throws Exception {
        var closed = new AtomicBoolean();
        var late = new AtomicInteger();
        var client = readyClient(closed, late);
        var queue = new LinkedBlockingQueue<Runnable>();
        var workers = Executors.newCachedThreadPool();
        var created = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var condition = new AtomicReference<BaseDurableOperation>();
        var setup = setup(client, workers, queue, closed);
        try {
            var parallel = setup.context.parallel(
                    "early",
                    ParallelConfig.builder()
                            .completionConfig(CompletionConfig.minSuccessful(1))
                            .build());
            var task = new AtomicReference<Runnable>();
            try (parallel) {
                parallel.branch("waiting", Integer.class, branch -> {
                    var future = branch.waitForConditionAsync(
                            "condition",
                            Integer.class,
                            (state, step) -> {
                                calls.incrementAndGet();
                                return WaitForConditionResult.continuePolling(state + 1);
                            },
                            waitConfig());
                    condition.set((BaseDurableOperation) future);
                    created.countDown();
                    return future.get();
                });
                parallel.branch("winner", String.class, branch -> {
                    await(created);
                    try {
                        task.set(queue.poll(3, TimeUnit.SECONDS));
                        assertNotNull(task.get());
                        condition.get().getRunningUserHandler().get(3, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    return "winner";
                });
            }
            var result = parallel.get();
            assertEquals(1, result.succeeded());
            assertFalse(condition.get().getCompletionFuture().isDone());
            var checkpointCount = client.getOperationUpdates().size();
            var closing = CompletableFuture.runAsync(setup.manager::close);
            assertTrue(setup.manager.closeEntered.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closing.get(150, TimeUnit.MILLISECONDS));
            CompletableFuture.runAsync(task.get()).get(3, TimeUnit.SECONDS);
            closing.get(3, TimeUnit.SECONDS);
            assertTrue(condition.get().getCompletionFuture().isCompletedExceptionally());
            assertEquals(1, calls.get());
            assertEquals(0, late.get());
            assertEquals(checkpointCount, client.getOperationUpdates().size());
            assertEquals(1, result.succeeded(), "Cleanup must not rewrite the stored early result");
        } finally {
            assertThrows(SuspendExecutionException.class, setup.manager::suspendExecution);
            Runnable pending;
            while ((pending = queue.poll()) != null) pending.run();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            setup.manager.close();
        }
    }

    private record Setup(ControlledManager manager, DurableContextImpl context) {}

    private Setup setup(
            LocalMemoryExecutionClient client,
            ExecutorService workers,
            BlockingQueue<Runnable> queue,
            AtomicBoolean closed) {
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ZERO)
                .withPollingStrategy(attempt -> Duration.ZERO)
                .build();
        var manager = new ControlledManager(input(client), config, queue, closed);
        rememberContext(manager);
        manager.registerActiveThread(null);
        manager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));
        var context = DurableContextImpl.createRootContext(manager, config, null);
        BaseContextImpl.setCurrentContext(context);
        return new Setup(manager, context);
    }

    private static WaitForConditionConfig<Integer> waitConfig() {
        return WaitForConditionConfig.<Integer>builder()
                .initialState(1)
                .waitStrategy((state, attempt) -> Duration.ofSeconds(1))
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static LocalMemoryExecutionClient readyClient(AtomicBoolean closed, AtomicInteger late) {
        return new LocalMemoryExecutionClient() {
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                if (closed.get()) late.incrementAndGet();
                var applied = super.checkpoint(arn, token, updates);
                advanceTime();
                var ready = super.checkpoint(arn, applied.checkpointToken(), List.of());
                var states = new LinkedHashMap<String, Operation>();
                applied.newExecutionState().operations().forEach(op -> states.put(op.id(), op));
                ready.newExecutionState().operations().forEach(op -> states.put(op.id(), op));
                return ready.toBuilder()
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(states.values())
                                .build())
                        .build();
            }
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "cancelled-observation", "after-old-worker-join", "already-suspended"})
    void normalCloseQuiescesAdmittedReadyWork(String mode) throws Exception {
        var closed = new AtomicBoolean();
        var lateBackend = new AtomicInteger();
        var predicates = new AtomicInteger();
        var queue = new LinkedBlockingQueue<Runnable>();
        var workers = new GateExecutor(mode.equals("after-old-worker-join"));
        var client = new LocalMemoryExecutionClient() {
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                if (closed.get()) lateBackend.incrementAndGet();
                var applied = super.checkpoint(arn, token, updates);
                advanceTime();
                var ready = super.checkpoint(arn, applied.checkpointToken(), List.of());
                var states = new LinkedHashMap<String, Operation>();
                applied.newExecutionState().operations().forEach(op -> states.put(op.id(), op));
                ready.newExecutionState().operations().forEach(op -> states.put(op.id(), op));
                return ready.toBuilder()
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(states.values())
                                .build())
                        .build();
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ZERO)
                .withPollingStrategy(attempt -> Duration.ZERO)
                .build();
        var manager = new ControlledManager(input(client), config, queue, closed);
        rememberContext(manager);
        manager.registerActiveThread(null);
        manager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));
        var context = DurableContextImpl.createRootContext(manager, config, null);
        BaseContextImpl.setCurrentContext(context);
        var body = new CompletableFuture<String>();
        var selected = manager.runUntilCompleteOrSuspend(body);
        try {
            context.waitForConditionAsync(
                    "condition",
                    Integer.class,
                    (state, step) -> {
                        predicates.incrementAndGet();
                        return state == 1
                                ? WaitForConditionResult.continuePolling(2)
                                : WaitForConditionResult.stopPolling(state);
                    },
                    WaitForConditionConfig.<Integer>builder()
                            .initialState(1)
                            .waitStrategy((state, attempt) -> Duration.ofSeconds(1))
                            .build());
            var task = queue.poll(3, TimeUnit.SECONDS);
            assertNotNull(task);
            assertTrue(workers.firstFinished.await(3, TimeUnit.SECONDS));
            if (mode.equals("cancelled-observation")) assertTrue(manager.observation.cancel(false));
            CompletableFuture<Void> taskRun = null;
            if (mode.equals("after-old-worker-join")) {
                taskRun = CompletableFuture.runAsync(task);
                assertTrue(workers.nextDispatchEntered.await(3, TimeUnit.SECONDS));
            }
            var stopped = mode.equals("already-suspended");
            if (stopped) assertThrows(SuspendExecutionException.class, manager::suspendExecution);
            else {
                body.complete("root-done");
                assertEquals("root-done", selected.get(3, TimeUnit.SECONDS));
            }
            var closing = CompletableFuture.runAsync(manager::close);
            assertTrue(manager.closeEntered.await(3, TimeUnit.SECONDS));
            boolean returnedWhileHeld;
            try {
                closing.get(150, TimeUnit.MILLISECONDS);
                returnedWhileHeld = true;
            } catch (TimeoutException expected) {
                returnedWhileHeld = false;
            }
            workers.releaseNextDispatch.countDown();
            if (taskRun == null) taskRun = CompletableFuture.runAsync(task);
            taskRun.get(3, TimeUnit.SECONDS);
            closing.get(3, TimeUnit.SECONDS);
            workers.shutdown();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            var returnedEarly = returnedWhileHeld;
            System.out.println("CONTINUATION_CLOSE mode=" + mode + " returnedWhileHeld=" + returnedEarly
                    + " predicateCalls=" + predicates.get() + " lateCheckpointAttempts="
                    + manager.lateCheckpointAttempts.get() + " lateBackendCalls=" + lateBackend.get());
            assertAll(
                    () -> {
                        if (!stopped) assertFalse(returnedEarly, "Close must wait for the actual admitted task");
                    },
                    () -> assertEquals(1, predicates.get(), "No new predicate may run after teardown"),
                    () -> assertEquals(0, manager.lateCheckpointAttempts.get()),
                    () -> assertEquals(0, lateBackend.get()));
            if (!stopped) assertEquals("root-done", selected.join());
        } finally {
            workers.releaseNextDispatch.countDown();
            Runnable pending;
            while ((pending = queue.poll()) != null) pending.run();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            manager.close();
        }
    }

    private static final class ControlledManager extends ExecutionManager {
        private final BlockingQueue<Runnable> queue;
        private final AtomicBoolean closed;
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final AtomicInteger lateCheckpointAttempts = new AtomicInteger();
        private volatile CompletableFuture<Void> observation;

        private ControlledManager(
                DurableExecutionInput input,
                DurableConfig config,
                BlockingQueue<Runnable> queue,
                AtomicBoolean closed) {
            super(input, config, null);
            this.queue = queue;
            this.closed = closed;
        }

        public CompletableFuture<Void> runCheckpointContinuation(BaseDurableOperation owner, Runnable task) {
            observation = super.runCheckpointContinuation(owner, task, queue::add);
            return observation;
        }

        public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
            if (closed.get()) lateCheckpointAttempts.incrementAndGet();
            return super.sendOperationUpdate(update);
        }

        public void close() {
            closeEntered.countDown();
            super.close();
            closed.set(true);
        }
    }

    private static final class GateExecutor extends ThreadPoolExecutor {
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private final boolean hold;
        private final CountDownLatch firstFinished = new CountDownLatch(1);
        private final CountDownLatch nextDispatchEntered = new CountDownLatch(1);
        private final CountDownLatch releaseNextDispatch = new CountDownLatch(1);

        private GateExecutor(boolean hold) {
            super(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
            this.hold = hold;
        }

        public void execute(Runnable task) {
            if (submitted.incrementAndGet() == 2 && hold) {
                nextDispatchEntered.countDown();
                try {
                    assertTrue(releaseNextDispatch.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            super.execute(task);
        }

        protected void afterExecute(Runnable task, Throwable failure) {
            if (finished.incrementAndGet() == 1) firstFinished.countDown();
        }
    }

    private static DurableExecutionInput input(LocalMemoryExecutionClient client) {
        var states = new ArrayList<>(client.getAllOperations());
        states.add(
                0,
                Operation.builder()
                        .id("execution")
                        .type(OperationType.EXECUTION)
                        .status(OperationStatus.STARTED)
                        .startTimestamp(Instant.EPOCH)
                        .executionDetails(ExecutionDetails.builder()
                                .inputPayload("\"input\"")
                                .build())
                        .build());
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/close/execution",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(states).build());
    }
}
