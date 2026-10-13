// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class InvocationFinalizationIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suspensionAndRetryWaitForHandlerFinallyAndOwnerEnd(boolean retry) throws Exception {
        var enteredFinally = new CountDownLatch(1);
        var releaseFinally = new CountDownLatch(1);
        var plugin = new RecordingPlugin();
        var original = retryError();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    try {
                        if (retry)
                            context.step("retry", String.class, step -> {
                                throw original;
                            });
                        else context.wait("pause", Duration.ofSeconds(1));
                        return "done";
                    } finally {
                        enteredFinally.countDown();
                        await(releaseFinally);
                    }
                },
                DurableConfig.builder().withPlugins(plugin).build());
        var caller = Executors.newSingleThreadExecutor();
        try {
            var response = caller.submit(() -> runner.run("input"));
            assertTrue(enteredFinally.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> response.get(700, TimeUnit.MILLISECONDS));
            assertTrue(plugin.ends.isEmpty(), "End must follow the handler's finally block");
            releaseFinally.countDown();
            if (retry) {
                var thrown = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
                assertSame(original, thrown.getCause());
            } else
                assertEquals(
                        ExecutionStatus.PENDING,
                        response.get(5, TimeUnit.SECONDS).getStatus());
            assertPaired(plugin, retry ? InvocationStatus.RETRYING : InvocationStatus.PENDING);
        } finally {
            releaseFinally.countDown();
            stop(caller);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void externallySelectedOutcomeSurvivesReturningOrThrowingFinally(boolean retry, boolean throwsInFinally)
            throws Exception {
        var enteredFinally = new CountDownLatch(1);
        var releaseFinally = new CountDownLatch(1);
        var manager = new AtomicReference<ExecutionManager>();
        var plugin = new RecordingPlugin();
        var original = retryError();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    manager.set(((DurableContextImpl) context).getExecutionManager());
                    try {
                        return "handler completed";
                    } finally {
                        enteredFinally.countDown();
                        await(releaseFinally);
                        if (throwsInFinally) throw new IllegalStateException("finally failed");
                    }
                },
                DurableConfig.builder().withPlugins(plugin).build());
        var caller = Executors.newSingleThreadExecutor();
        try {
            var response = caller.submit(() -> runner.run("input"));
            assertTrue(enteredFinally.await(5, TimeUnit.SECONDS));
            if (retry)
                assertSame(
                        original,
                        assertThrows(
                                UnrecoverableDurableExecutionException.class,
                                () -> manager.get().terminateExecution(original)));
            else
                assertThrows(
                        SuspendExecutionException.class, () -> manager.get().suspendExecution());
            assertThrows(TimeoutException.class, () -> response.get(700, TimeUnit.MILLISECONDS));
            releaseFinally.countDown();
            if (retry) {
                var thrown = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
                assertSame(original, thrown.getCause());
            } else
                assertEquals(
                        ExecutionStatus.PENDING,
                        response.get(5, TimeUnit.SECONDS).getStatus());
            assertPaired(plugin, retry ? InvocationStatus.RETRYING : InvocationStatus.PENDING);
            assertSame(retry ? original : null, plugin.ends.get(0).executionError());
            assertNull(plugin.ends.get(0).executionResult());
        } finally {
            releaseFinally.countDown();
            stop(caller);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "step,async,true",
        "step,async,false",
        "condition,async,true",
        "condition,async,false",
        "termination,async,true",
        "termination,async,false",
        "step,inline-root,true",
        "step,inline-root,false",
        "condition,inline-root,true",
        "condition,inline-root,false",
        "termination,inline-root,true",
        "termination,inline-root,false",
        "step,submit-wait,true",
        "step,submit-wait,false",
        "condition,submit-wait,true",
        "condition,submit-wait,false",
        "termination,submit-wait,true",
        "termination,submit-wait,false"
    })
    void naturalManagerOutcomePrecedesFastThrowingFinally(String kind, String mode, boolean withPlugin)
            throws Exception {
        var workers = new OutcomeExecutor(mode);
        var releaseOperation = new CountDownLatch(mode.equals("submit-wait") ? 0 : 1);
        var aboutToWait = new CountDownLatch(1);
        var completedStepCalls = new AtomicInteger();
        var targetCalls = new AtomicInteger();
        var throwFinally = new AtomicBoolean(true);
        var original = retryError();
        var plugin = new RecordingPlugin();
        var builder = DurableConfig.builder().withExecutorService(workers);
        if (withPlugin) builder.withPlugins(plugin);
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("saved", String.class, step -> {
                        completedStepCalls.incrementAndGet();
                        return "saved";
                    });
                    DurableFuture<?> future;
                    if (kind.equals("condition")) {
                        future = context.waitForConditionAsync(
                                "condition",
                                Integer.class,
                                (state, step) -> {
                                    await(releaseOperation);
                                    return targetCalls.incrementAndGet() == 1
                                            ? WaitForConditionResult.continuePolling(state + 1)
                                            : WaitForConditionResult.stopPolling(state);
                                },
                                WaitForConditionConfig.<Integer>builder()
                                        .initialState(0)
                                        .waitStrategy((state, attempt) -> Duration.ofSeconds(1))
                                        .build());
                    } else {
                        future = context.stepAsync(
                                "target",
                                String.class,
                                step -> {
                                    await(releaseOperation);
                                    if (targetCalls.incrementAndGet() == 1) {
                                        if (kind.equals("termination")) throw original;
                                        throw new IllegalArgumentException("retry step");
                                    }
                                    return "target";
                                },
                                StepConfig.builder()
                                        .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                                        .build());
                    }
                    // This schedules the existing completion window; suspension/termination is triggered only by the
                    // real
                    // operation. The root's dependent wait is registered later and wakes before this earlier callback
                    // drains.
                    ((BaseDurableOperation) future).getCompletionFuture().whenComplete((value, failure) -> {
                        if (failure != null && !mode.equals("submit-wait") && throwFinally.get())
                            await(workers.rootReturned);
                    });
                    try {
                        aboutToWait.countDown();
                        future.get();
                        return "done";
                    } finally {
                        if (throwFinally.get()) throw new IllegalStateException("fast handler finally");
                    }
                },
                builder.build());
        var caller = Executors.newSingleThreadExecutor();
        try {
            var response = caller.submit(() -> runner.run("input"));
            await(aboutToWait);
            if (!mode.equals("submit-wait")) {
                var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < until) {
                    var root = workers.rootOwner.get();
                    if (root != null
                            && root.getState() == Thread.State.WAITING
                            && Arrays.stream(root.getStackTrace())
                                    .anyMatch(frame -> frame.getMethodName().equals("waitForOperationCompletion")))
                        break;
                    LockSupport.parkNanos(100_000);
                }
                assertEquals(Thread.State.WAITING, workers.rootOwner.get().getState());
                releaseOperation.countDown();
            }
            if (kind.equals("termination")) {
                assertSame(
                        original,
                        assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS))
                                .getCause());
            } else {
                assertEquals(
                        ExecutionStatus.PENDING,
                        response.get(5, TimeUnit.SECONDS).getStatus());
            }
            if (withPlugin)
                assertPaired(plugin, kind.equals("termination") ? InvocationStatus.RETRYING : InvocationStatus.PENDING);
            throwFinally.set(false);
            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("input").getStatus());
            assertEquals(1, completedStepCalls.get(), "The completed step must replay without another side effect");
        } finally {
            releaseOperation.countDown();
            workers.rootReturned.countDown();
            stop(caller);
            stop(workers);
        }
    }

    private static final class OutcomeExecutor extends AbstractExecutorService {
        private final String mode;
        private final ExecutorService delegate = Executors.newCachedThreadPool();
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final AtomicReference<Thread> rootOwner = new AtomicReference<>();
        private final CountDownLatch rootReturned = new CountDownLatch(1);

        private OutcomeExecutor(String mode) {
            this.mode = mode;
        }

        public void execute(Runnable task) {
            var root = first.getAndSet(false);
            Runnable wrapped = () -> {
                if (root) rootOwner.set(Thread.currentThread());
                try {
                    task.run();
                } finally {
                    if (root) rootReturned.countDown();
                }
            };
            if (root && mode.equals("inline-root")) wrapped.run();
            else if (mode.equals("submit-wait")) {
                try {
                    delegate.submit(wrapped).get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            } else delegate.execute(wrapped);
        }

        public void shutdown() {
            delegate.shutdown();
        }

        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    @Test
    void hooksStayPairedAcrossStepRetryAndReplayWithoutRepeatingCompletedWork() {
        var plugin = new RecordingPlugin();
        var sideEffects = new AtomicInteger();
        var attempts = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var result = context.step("completed", Integer.class, step -> sideEffects.incrementAndGet());
                    context.step(
                            "retry",
                            String.class,
                            step -> {
                                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("try again");
                                return "retried";
                            },
                            StepConfig.builder()
                                    .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                                    .build());
                    context.wait("pause", Duration.ofSeconds(1));
                    return result;
                },
                DurableConfig.builder().withPlugins(plugin).build());

        var output = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, output.getStatus());
        assertEquals(1, output.getResult(Integer.class));
        assertEquals(1, sideEffects.get(), "the completed step must replay without repeating user code");
        assertEquals(2, attempts.get());
        assertTrue(plugin.starts.size() >= 3, "retry and wait should each suspend before the successful invocation");
        assertEquals(plugin.starts.size(), plugin.ends.size());
        assertEquals(plugin.startThreads, plugin.endThreads);
        assertTrue(plugin.endLocalValues.stream().allMatch("invocation"::equals));
        assertEquals(InvocationStatus.PENDING, plugin.ends.get(0).invocationStatus());
        assertEquals(
                InvocationStatus.SUCCEEDED,
                plugin.ends.get(plugin.ends.size() - 1).invocationStatus());
        assertTrue(plugin.starts.get(0).isFirstInvocation());
        assertFalse(plugin.starts.get(plugin.starts.size() - 1).isFirstInvocation());
    }

    private static void assertPaired(RecordingPlugin plugin, InvocationStatus status) {
        assertEquals(1, plugin.starts.size());
        assertEquals(1, plugin.ends.size());
        assertEquals(status, plugin.ends.get(0).invocationStatus());
        assertEquals(plugin.startThreads, plugin.endThreads);
        assertEquals(List.of("invocation"), plugin.endLocalValues);
    }

    private static UnrecoverableDurableExecutionException retryError() {
        return new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorMessage("retry invocation").build(), true);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("finally not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static class RecordingPlugin implements DurableExecutionPlugin {
        final ThreadLocal<String> local = new ThreadLocal<>();
        final List<InvocationInfo> starts = new ArrayList<>();
        final List<InvocationEndInfo> ends = new ArrayList<>();
        final List<Thread> startThreads = new ArrayList<>();
        final List<Thread> endThreads = new ArrayList<>();
        final List<String> endLocalValues = new ArrayList<>();

        @Override
        public void onInvocationStart(InvocationInfo info) {
            starts.add(info);
            startThreads.add(Thread.currentThread());
            local.set("invocation");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            ends.add(info);
            endThreads.add(Thread.currentThread());
            endLocalValues.add(local.get());
            local.remove();
        }
    }
}
