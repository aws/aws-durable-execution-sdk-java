// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class WaitForConditionReadinessIntegrationTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 25})
    void readyInRetryResponseContinuesWithoutSuspensionAndReplaysStoredResult(int threshold) throws Exception {
        try (var run = new Run(
                new ImmediateReadyClient(),
                Executors.newCachedThreadPool(),
                value -> value >= threshold
                        ? WaitForConditionResult.stopPolling(value)
                        : WaitForConditionResult.continuePolling(value + 1))) {
            var first = run.start().get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.SUCCEEDED, first.status(), "READY work must not produce an invalid PENDING");
            assertEquals("\"" + threshold + "\"", first.result());
            assertEquals(threshold, run.checks.get());
            var updates = run.client.getOperationUpdates().size();
            var replay = run.start().get(5, TimeUnit.SECONDS);
            assertEquals(first, replay);
            assertEquals(threshold, run.checks.get(), "Completed checks must not be repeated on replay");
            assertEquals(updates, run.client.getOperationUpdates().size());
        }
    }

    @Test
    void immediateReadyFailureRemainsCheckpointedOnReplay() throws Exception {
        try (var run = new Run(new ImmediateReadyClient(), Executors.newCachedThreadPool(), value -> {
            if (value == 2) throw new IllegalStateException("predicate failure");
            return WaitForConditionResult.continuePolling(value + 1);
        })) {
            var first = run.start().get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.FAILED, first.status());
            assertEquals("predicate failure", first.error().errorMessage());
            var updates = run.client.getOperationUpdates().size();
            var replay = run.start().get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.FAILED, replay.status());
            assertEquals(first.error().errorType(), replay.error().errorType());
            assertEquals(first.error().errorMessage(), replay.error().errorMessage());
            assertEquals(2, run.checks.get());
            assertEquals(updates, run.client.getOperationUpdates().size());
        }
    }

    @Test
    void pendingRetryStillSuspendsAndResumesWhenBackendBecomesReady() throws Exception {
        try (var run = new Run(
                new LocalMemoryExecutionClient(),
                Executors.newCachedThreadPool(),
                value -> value == 2
                        ? WaitForConditionResult.stopPolling(value)
                        : WaitForConditionResult.continuePolling(value + 1))) {
            var first = run.start().get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.PENDING, first.status());
            assertEquals(
                    OperationStatus.PENDING,
                    run.client.getOperationByName("condition").status());
            assertEquals(1, run.checks.get());
            assertTrue(run.client.advanceTime());
            var resumed = run.start().get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.SUCCEEDED, resumed.status());
            assertEquals("\"2\"", resumed.result());
            assertEquals(2, run.checks.get());
            assertEquals(resumed, run.start().get(5, TimeUnit.SECONDS));
            assertEquals(2, run.checks.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void asynchronousReadyHandoffDoesNotDeadlockOrLoseItsActivityLease(
            boolean holdWorkerExit, boolean holdExecutorReturn) throws Exception {
        var gate = new HandoffGate(holdWorkerExit, holdExecutorReturn);
        var client = new DelayedReadyClient(gate);
        try (var mdc = new MdcClearGate(gate);
                var run = new Run(client, gate, value -> {
                    if (value == 1) {
                        gate.firstWorker.set(Thread.currentThread());
                        return WaitForConditionResult.continuePolling(2);
                    }
                    gate.nextCheckEntered.countDown();
                    await(gate.releaseNextCheck);
                    return WaitForConditionResult.stopPolling(value);
                })) {
            try {
                var result = run.start();
                await(gate.pollEntered);
                if (holdWorkerExit) await(gate.workerExitEntered);
                else await(gate.executorReturnEntered);
                assertTrue(client.advanceTime());
                gate.releaseReadyResponse.countDown();
                await(gate.readyResponseReturned);
                awaitCheckpointHandoff(gate.pollThread.get());
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
                assertEquals(1, run.checks.get());
                gate.releaseWorkerExit.countDown();
                if (holdExecutorReturn) await(gate.executorReturnEntered);
                gate.releaseExecutorReturn.countDown();
                await(gate.nextCheckEntered);
                assertThrows(
                        TimeoutException.class,
                        () -> result.get(100, TimeUnit.MILLISECONDS),
                        "The next check must stay active after the old worker deregisters and the checkpoint returns");
                gate.releaseNextCheck.countDown();
                assertEquals(
                        ExecutionStatus.SUCCEEDED,
                        result.get(5, TimeUnit.SECONDS).status());
                assertEquals(2, run.checks.get());
                assertEquals(4, run.stateReads.get(), "In-process continuation must reuse normalized state");
                assertEquals(
                        ExecutionStatus.SUCCEEDED,
                        run.start().get(5, TimeUnit.SECONDS).status());
                assertEquals(2, run.checks.get());
                assertEquals(5, run.stateReads.get(), "Completed replay only decodes the stored result");
            } finally {
                gate.releaseAll();
            }
        }
    }

    private static void awaitCheckpointHandoff(Thread checkpoint) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var stack = checkpoint.getStackTrace();
            if (checkpoint.getState() == Thread.State.WAITING
                    && Arrays.stream(stack)
                            .anyMatch(frame -> frame.getClassName().endsWith("WaitForConditionOperation"))
                    && Arrays.stream(stack)
                            .anyMatch(frame -> frame.getClassName().equals(CompletableFuture.class.getName())
                                    && frame.getMethodName().equals("join"))) {
                var bean = ManagementFactory.getThreadMXBean();
                if (bean.isObjectMonitorUsageSupported()) {
                    var info = bean.getThreadInfo(new long[] {checkpoint.getId()}, true, true)[0];
                    System.out.println(
                            "READY_HANDOFF checkpoint monitors=" + Arrays.toString(info.getLockedMonitors()));
                }
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("The READY callback did not reach the controlled old-worker handoff window");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled readiness gate was not released");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static final class ImmediateReadyClient extends LocalMemoryExecutionClient {
        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            var applied = super.checkpoint(arn, token, updates);
            // Advance the test backend's virtual time before materializing the response. The configured
            // one-second delay is valid under Lambda's minimum; no zero-delay service request is assumed.
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
    }

    private static final class DelayedReadyClient extends LocalMemoryExecutionClient {
        private final HandoffGate gate;
        private final AtomicBoolean firstPoll = new AtomicBoolean(true);

        private DelayedReadyClient(HandoffGate gate) {
            this.gate = gate;
        }

        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            if (updates.isEmpty() && firstPoll.compareAndSet(true, false)) {
                gate.pollThread.set(Thread.currentThread());
                gate.pollEntered.countDown();
                await(gate.releaseReadyResponse);
                var response = super.checkpoint(arn, token, updates);
                gate.readyResponseReturned.countDown();
                return response;
            }
            return super.checkpoint(arn, token, updates);
        }
    }

    private static final class HandoffGate extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newCachedThreadPool();
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicBoolean exitHeld = new AtomicBoolean();
        private final AtomicReference<Thread> firstWorker = new AtomicReference<>();
        private final AtomicReference<Thread> pollThread = new AtomicReference<>();
        private final CountDownLatch pollEntered = new CountDownLatch(1);
        private final CountDownLatch releaseReadyResponse = new CountDownLatch(1);
        private final CountDownLatch readyResponseReturned = new CountDownLatch(1);
        private final CountDownLatch workerExitEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWorkerExit = new CountDownLatch(1);
        private final CountDownLatch executorReturnEntered = new CountDownLatch(1);
        private final CountDownLatch releaseExecutorReturn = new CountDownLatch(1);
        private final CountDownLatch nextCheckEntered = new CountDownLatch(1);
        private final CountDownLatch releaseNextCheck = new CountDownLatch(1);
        private final boolean holdWorkerExit;
        private final boolean holdExecutorReturn;

        private HandoffGate(boolean holdWorkerExit, boolean holdExecutorReturn) {
            this.holdWorkerExit = holdWorkerExit;
            this.holdExecutorReturn = holdExecutorReturn;
        }

        @Override
        public void execute(Runnable task) {
            var number = submitted.incrementAndGet();
            var finished = new CountDownLatch(1);
            delegate.execute(() -> {
                try {
                    task.run();
                } finally {
                    finished.countDown();
                }
            });
            // Completing work before execute() returns is permitted by Executor's contract.
            if (holdExecutorReturn && number == 2) {
                await(finished);
                executorReturnEntered.countDown();
                await(releaseExecutorReturn);
            }
        }

        private void releaseAll() {
            releaseReadyResponse.countDown();
            releaseWorkerExit.countDown();
            releaseExecutorReturn.countDown();
            releaseNextCheck.countDown();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            releaseAll();
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    private static final class MdcClearGate implements AutoCloseable {
        private final MDCAdapter previous = MDC.getMDCAdapter();
        private final Method setter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);

        private MdcClearGate(HandoffGate gate) throws Exception {
            setter.setAccessible(true);
            setter.invoke(null, new MDCAdapter() {
                public void clear() {
                    if (gate.holdWorkerExit
                            && Thread.currentThread() == gate.firstWorker.get()
                            && gate.exitHeld.compareAndSet(false, true)) {
                        gate.workerExitEntered.countDown();
                        await(gate.releaseWorkerExit);
                    }
                    previous.clear();
                }

                public void put(String key, String value) {
                    previous.put(key, value);
                }

                public String get(String key) {
                    return previous.get(key);
                }

                public void remove(String key) {
                    previous.remove(key);
                }

                public Map<String, String> getCopyOfContextMap() {
                    return previous.getCopyOfContextMap();
                }

                public void setContextMap(Map<String, String> values) {
                    previous.setContextMap(values);
                }

                public void pushByKey(String key, String value) {
                    previous.pushByKey(key, value);
                }

                public String popByKey(String key) {
                    return previous.popByKey(key);
                }

                public Deque<String> getCopyOfDequeByKey(String key) {
                    return previous.getCopyOfDequeByKey(key);
                }

                public void clearDequeByKey(String key) {
                    previous.clearDequeByKey(key);
                }
            });
        }

        public void close() throws Exception {
            setter.invoke(null, previous);
        }
    }

    private static final class Run implements AutoCloseable {
        private final LocalMemoryExecutionClient client;
        private final ExecutorService workers;
        private final ExecutorService caller = Executors.newSingleThreadExecutor();
        private final AtomicInteger checks = new AtomicInteger();
        private final AtomicInteger stateReads = new AtomicInteger();
        private final SerDes stateSerDes = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                return delegate.serialize(value);
            }

            public <T> T deserialize(String value, TypeToken<T> type) {
                stateReads.incrementAndGet();
                return delegate.deserialize(value, type);
            }
        };
        private final IntFunction<WaitForConditionResult<Integer>> check;
        private final DurableConfig config;
        private final WaitForConditionConfig<Integer> wait = WaitForConditionConfig.<Integer>builder()
                .initialState(1)
                .serDes(stateSerDes)
                .waitStrategy((value, attempt) -> Duration.ofSeconds(1))
                .build();

        private Run(
                LocalMemoryExecutionClient client,
                ExecutorService workers,
                IntFunction<WaitForConditionResult<Integer>> check) {
            this.client = client;
            this.workers = workers;
            this.check = check;
            config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withDurableExecutionClient(client)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPollingStrategy(attempt -> Duration.ZERO)
                    .build();
        }

        private Future<DurableExecutionOutput> start() {
            var execution = Operation.builder()
                    .id("execution")
                    .type(OperationType.EXECUTION)
                    .status(OperationStatus.STARTED)
                    .startTimestamp(Instant.EPOCH)
                    .executionDetails(
                            ExecutionDetails.builder().inputPayload("\"input\"").build())
                    .build();
            var history = new ArrayList<>(client.getAllOperations());
            history.add(0, execution);
            var input = new DurableExecutionInput(
                    "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/ready/execution",
                    "token",
                    CheckpointUpdatedExecutionState.builder()
                            .operations(history)
                            .build());
            return caller.submit(() -> DurableExecutor.execute(
                    input,
                    null,
                    TypeToken.get(String.class),
                    (value, ctx) -> String.valueOf(ctx.waitForCondition(
                            "condition",
                            Integer.class,
                            (state, step) -> {
                                checks.incrementAndGet();
                                return check.apply(state);
                            },
                            wait)),
                    config));
        }

        @Override
        public void close() throws Exception {
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
