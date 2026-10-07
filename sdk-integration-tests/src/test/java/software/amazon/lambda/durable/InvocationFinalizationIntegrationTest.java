// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.ExecutionStatus;
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
