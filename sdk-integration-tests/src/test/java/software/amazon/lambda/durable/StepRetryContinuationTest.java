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
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class StepRetryContinuationTest {
    @ParameterizedTest
    @ValueSource(strings = {"async", "reject", "direct", "submit-wait"})
    void realPendingStepRetryMustNotLoseDispatchFailureOrBlockItsCheckpointBatcher(String mode) throws Exception {
        var armed = new AtomicBoolean();
        var calls = new AtomicInteger();
        var pollEntered = new CountDownLatch(1);
        var releasePoll = new CountDownLatch(1);
        var pollOnce = new AtomicBoolean(true);
        var dispatchTimedOut = new AtomicBoolean();
        var dispatchThread = new AtomicReference<String>();
        var dispatchFailure = new RejectedExecutionException("resumed step rejected");
        var managerRef = new AtomicReference<ExecutionManager>();
        var pendingCall = new AtomicReference<Future<?>>();
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                if (armed.get() && updates.isEmpty() && pollOnce.compareAndSet(true, false)) {
                    pollEntered.countDown();
                    await(releasePoll);
                }
                return super.checkpoint(arn, token, updates);
            }
        };
        var workers =
                new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>()) {
                    @Override
                    public void execute(Runnable task) {
                        boolean resume = armed.get()
                                && Arrays.stream(Thread.currentThread().getStackTrace())
                                        .anyMatch(frame -> frame.getClassName().endsWith(".StepOperation")
                                                && frame.getMethodName().equals("executeStepLogic"));
                        if (resume) {
                            dispatchThread.set(Thread.currentThread().getName());
                            if (mode.equals("reject")) throw dispatchFailure;
                            if (mode.equals("direct")) {
                                task.run();
                                return;
                            }
                            if (mode.equals("submit-wait")) {
                                var done = new CompletableFuture<Void>();
                                super.execute(() -> {
                                    try {
                                        task.run();
                                    } finally {
                                        done.complete(null);
                                    }
                                });
                                try {
                                    done.get(2, TimeUnit.SECONDS);
                                } catch (TimeoutException expected) {
                                    dispatchTimedOut.set(true);
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                                return;
                            }
                        }
                        super.execute(task);
                    }
                };
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO)
                .withPollingStrategy(attempt -> Duration.ZERO)
                .build();
        var stepConfig = StepConfig.builder()
                .retryStrategy((error, attempt) ->
                        attempt < 2 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail())
                .build();
        var caller = Executors.newSingleThreadExecutor();
        BiFunction<String, DurableContext, String> handler = (value, context) -> {
            managerRef.set(((BaseContextImpl) context).getExecutionManager());
            var future = context.stepAsync(
                    "retry-step",
                    String.class,
                    step -> {
                        if (calls.incrementAndGet() == 1) throw new IllegalStateException("first attempt");
                        return "stored";
                    },
                    stepConfig);
            if (armed.get()) await(pollEntered);
            return future.get();
        };
        try {
            var first = caller.submit(() ->
                    DurableExecutor.execute(input(List.of()), null, TypeToken.get(String.class), handler, config));
            pendingCall.set(first);
            assertEquals(ExecutionStatus.PENDING, first.get(5, TimeUnit.SECONDS).status());
            var pending = new ArrayList<>(client.getAllOperations());
            assertEquals(
                    OperationStatus.PENDING,
                    client.getOperationByName("retry-step").status());
            assertTrue(client.advanceTime());
            armed.set(true);
            var second = caller.submit(
                    () -> DurableExecutor.execute(input(pending), null, TypeToken.get(String.class), handler, config));
            pendingCall.set(second);
            assertTrue(pollEntered.await(5, TimeUnit.SECONDS));
            releasePoll.countDown();
            DurableExecutionOutput output = null;
            Throwable failure = null;
            try {
                output = second.get(6, TimeUnit.SECONDS);
            } catch (ExecutionException observed) {
                failure = observed.getCause();
            }
            System.out.println("STEP_RETRY_PUBLIC mode=" + mode + " thread=" + dispatchThread.get() + " output="
                    + (output == null ? failure : output.status()) + " backend="
                    + client.getOperationByName("retry-step").status()
                    + " calls=" + calls.get() + " dispatchTimedOut=" + dispatchTimedOut.get());
            assertFalse(
                    dispatchTimedOut.get(), "Submit-and-wait dispatch must not block the batcher needed by its step");
            if (mode.equals("reject")) {
                var retry = assertInstanceOf(UnrecoverableDurableExecutionException.class, failure);
                assertTrue(retry.isRetryable());
                assertSame(dispatchFailure, retry.getCause());
                assertNull(output);
                assertEquals(1, calls.get());
                assertEquals(
                        OperationStatus.READY,
                        client.getOperationByName("retry-step").status());
            } else assertEquals(ExecutionStatus.SUCCEEDED, output.status());
            armed.set(false);
            var resumed = caller.submit(() -> DurableExecutor.execute(
                    input(client.getAllOperations()), null, TypeToken.get(String.class), handler, config));
            pendingCall.set(resumed);
            assertEquals(
                    ExecutionStatus.SUCCEEDED, resumed.get(5, TimeUnit.SECONDS).status());
            assertEquals(2, calls.get(), "Completed step bodies do not repeat on replay");
        } finally {
            releasePoll.countDown();
            var manager = managerRef.get();
            if (pendingCall.get() != null
                    && !pendingCall.get().isDone()
                    && manager != null
                    && !manager.isExecutionCompletedExceptionally()) {
                try {
                    manager.terminateExecution(new UnrecoverableDurableExecutionException(
                            ErrorObject.builder().errorMessage("probe cleanup").build(), true));
                } catch (UnrecoverableDurableExecutionException expected) {
                }
            }
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static DurableExecutionInput input(List<Operation> operations) {
        var all = new ArrayList<Operation>();
        all.add(Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build());
        all.addAll(operations);
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/step-retry/execution",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(all).build());
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void liveRetryRetiresItsWorkerBeforeReplacementWithoutLosingActivity(
            boolean holdWorkerExit, boolean holdExecutorReturn) throws Exception {
        var gate = new HandoffGate(holdWorkerExit, holdExecutorReturn);
        var client = new DelayedReadyClient(gate);
        var calls = new AtomicInteger();
        var caller = Executors.newSingleThreadExecutor();
        var config = DurableConfig.builder()
                .withExecutorService(gate)
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO)
                .withPollingStrategy(attempt -> Duration.ZERO)
                .build();
        var retry = StepConfig.builder()
                .retryStrategy((error, attempt) ->
                        attempt == 1 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail())
                .build();
        BiFunction<String, DurableContext, String> handler = (value, context) -> {
            var step = context.stepAsync(
                    "retry-step",
                    String.class,
                    stepContext -> {
                        if (calls.incrementAndGet() == 1) {
                            gate.firstWorker.set(Thread.currentThread());
                            throw new IllegalStateException("retry once");
                        }
                        gate.nextCheckEntered.countDown();
                        await(gate.releaseNextCheck);
                        return "stored";
                    },
                    retry);
            // Keep the root active until the live retry has registered its backend poll.
            await(gate.pollEntered);
            return step.get();
        };
        try (var mdc = new MdcClearGate(gate)) {
            try {
                var result = caller.submit(() ->
                        DurableExecutor.execute(input(List.of()), null, TypeToken.get(String.class), handler, config));
                await(gate.pollEntered);
                if (holdWorkerExit) await(gate.workerExitEntered);
                else await(gate.executorReturnEntered);
                assertTrue(client.advanceTime());
                gate.releaseReadyResponse.countDown();
                await(gate.readyResponseReturned);
                awaitCheckpointHandoff(gate.pollThread.get());
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
                assertEquals(1, calls.get());
                gate.releaseWorkerExit.countDown();
                if (holdExecutorReturn) await(gate.executorReturnEntered);
                gate.releaseExecutorReturn.countDown();
                await(gate.nextCheckEntered);
                assertThrows(
                        TimeoutException.class,
                        () -> result.get(100, TimeUnit.MILLISECONDS),
                        "The replacement retains activity after its previous worker deregisters");
                gate.releaseNextCheck.countDown();
                var completed = result.get(5, TimeUnit.SECONDS);
                assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
                assertEquals("\"stored\"", completed.result());
                assertEquals(
                        OperationStatus.SUCCEEDED,
                        client.getOperationByName("retry-step").status());
                var replay = caller.submit(() -> DurableExecutor.execute(
                        input(client.getAllOperations()), null, TypeToken.get(String.class), handler, config));
                assertEquals(completed, replay.get(5, TimeUnit.SECONDS));
                assertEquals(2, calls.get(), "The completed retry body is skipped on replay");
            } finally {
                gate.releaseAll();
                caller.shutdownNow();
                gate.shutdownNow();
                assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(gate.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void awaitCheckpointHandoff(Thread checkpoint) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                var continuation = entry.getKey();
                var stack = entry.getValue();
                if (continuation.getState() != Thread.State.WAITING
                        || !continuation.getName().startsWith("durable-sdk-internal-")
                        || Arrays.stream(stack)
                                .noneMatch(frame -> frame.getClassName().endsWith("StepOperation"))
                        || Arrays.stream(stack)
                                .noneMatch(frame -> frame.getClassName().equals(CompletableFuture.class.getName())
                                        && frame.getMethodName().equals("join"))) continue;
                assertNotSame(checkpoint, continuation, "Worker publication must not block the checkpoint callback");
                assertTrue(Arrays.stream(stack)
                        .noneMatch(frame -> frame.getClassName().endsWith("ApiRequestDelayedBatcher")));
                if (Arrays.stream(checkpoint.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().endsWith("CheckpointManager")
                                && frame.getMethodName().equals("checkpointBatch"))) continue;
                var bean = ManagementFactory.getThreadMXBean();
                if (bean.isObjectMonitorUsageSupported()) {
                    var info = bean.getThreadInfo(new long[] {continuation.getId()}, true, true)[0];
                    assertEquals(0, info.getLockedMonitors().length);
                    System.out.println("READY_HANDOFF continuation=" + continuation.getName() + " checkpoint="
                            + checkpoint.getName() + " continuationMonitors=[] checkpointBatchReturned=true");
                }
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("The independent READY continuation did not reach the controlled old-worker handoff window");
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
}
