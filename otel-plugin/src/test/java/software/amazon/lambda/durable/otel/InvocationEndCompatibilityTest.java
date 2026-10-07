// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

/** Verifies invocation-hook thread affinity, including executors that complete tasks before submission returns. */
class InvocationEndCompatibilityTest {
    @ParameterizedTest
    @CsvSource({
        "false,false,false", "true,false,false", "false,true,false", "true,true,false",
        "false,false,true", "true,false,true", "false,true,true", "true,true,true"
    })
    void invocationEndRunsOnHandlerThread(boolean mixed, boolean precompleted, boolean pending) throws Exception {
        var local = new ThreadLocal<String>();
        var observations = new ArrayList<Observation>();
        var callerThread = new AtomicReference<Thread>();
        var caller = Executors.newSingleThreadExecutor(task -> {
            var thread = daemon(task, "legacy-probe-caller");
            callerThread.set(thread);
            return thread;
        });
        var workers =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(),
                        task -> daemon(task, "legacy-probe-worker")) {
                    @Override
                    public void execute(Runnable task) {
                        var finished = new CountDownLatch(1);
                        super.execute(() -> {
                            try {
                                task.run();
                            } finally {
                                finished.countDown();
                            }
                        });
                        if (precompleted) await(finished);
                    }
                };
        try {
            for (int round = 1; round <= 2; round++) {
                var marker = "inv" + round;
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var endValue = new AtomicReference<String>();
                var startThread = new AtomicReference<Thread>();
                var endThread = new AtomicReference<Thread>();
                var previous = new AtomicReference<String>();
                var endCalls = new AtomicInteger();
                var endOrder = new CopyOnWriteArrayList<String>();
                var legacy = new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationStart(InvocationInfo info) {
                        startThread.set(Thread.currentThread());
                        previous.set(local.get());
                        local.set(marker);
                    }

                    @Override
                    public void onInvocationEnd(InvocationEndInfo info) {
                        endCalls.incrementAndGet();
                        endOrder.add("legacy");
                        endThread.set(Thread.currentThread());
                        endValue.set(local.get());
                        local.remove();
                    }
                };
                var plugins = new ArrayList<DurableExecutionPlugin>();
                plugins.add(legacy);
                if (mixed)
                    plugins.add(
                            0,
                            new InvocationOtelPlugin(
                                    SdkTracerProvider.builder(),
                                    OtelPluginConfig.builder().enableMdc(false).build()) {
                                @Override
                                public void onInvocationEnd(InvocationEndInfo info) {
                                    endOrder.add("otel");
                                    super.onInvocationEnd(info);
                                }
                            });
                var config = DurableConfig.builder()
                        .withExecutorService(workers)
                        .withDurableExecutionClient(new LocalMemoryExecutionClient())
                        .withCheckpointDelay(Duration.ZERO)
                        .withPlugins(plugins.toArray(DurableExecutionPlugin[]::new))
                        .build();
                var result = caller.submit(() -> DurableExecutor.execute(
                        input(marker),
                        null,
                        TypeToken.get(String.class),
                        (value, context) -> {
                            entered.countDown();
                            if (!precompleted) await(release);
                            if (pending) context.wait("pause", Duration.ofSeconds(1));
                            return "done";
                        },
                        config));
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    if (!precompleted) {
                        awaitCallerJoin(callerThread.get());
                        release.countDown();
                    }
                    assertEquals(
                            pending ? ExecutionStatus.PENDING : ExecutionStatus.SUCCEEDED,
                            result.get(5, TimeUnit.SECONDS).status());
                    var after = workers.submit(local::get).get(3, TimeUnit.SECONDS);
                    observations.add(new Observation(
                            startThread.get(), endThread.get(), endValue.get(), after, previous.get(), endCalls.get()));
                    assertEquals(
                            mixed ? List.of("legacy", "otel") : List.of("legacy"),
                            endOrder,
                            "all end hooks unwind registration order and execute once");
                } finally {
                    release.countDown();
                }
            }
            for (int index = 0; index < observations.size(); index++) {
                var observation = observations.get(index);
                assertEquals(1, observation.endCalls());
                assertSame(observation.startThread(), observation.endThread());
                assertNotSame(callerThread.get(), observation.endThread());
                assertEquals("inv" + (index + 1), observation.endValue());
                assertNull(observation.workerAfter(), "end hook clears its original worker value");
                assertNull(observation.previous(), "reused worker must not retain the prior invocation");
            }
        } finally {
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void suspendedInvocationWaitsForEndOnTheReusedWorker() throws Exception {
        var local = new ThreadLocal<String>();
        var callerThread = new AtomicReference<Thread>();
        var callers = Executors.newSingleThreadExecutor(task -> {
            var thread = daemon(task, "scoped-handoff-caller");
            callerThread.set(thread);
            return thread;
        });
        var workers = Executors.newSingleThreadExecutor(task -> daemon(task, "scoped-handoff-worker"));
        try {
            for (var round = 0; round < 3; round++) {
                var marker = "scoped-" + round;
                var scoped = new PausingOtelPlugin();
                var previous = new AtomicReference<String>();
                var observed = new AtomicReference<String>();
                var owner = new AtomicReference<Thread>();
                var endThread = new AtomicReference<Thread>();
                var legacy = new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationStart(InvocationInfo info) {
                        owner.set(Thread.currentThread());
                        previous.set(local.get());
                        local.set(marker);
                    }

                    @Override
                    public void onInvocationEnd(InvocationEndInfo info) {
                        endThread.set(Thread.currentThread());
                        observed.set(local.get());
                        local.remove();
                    }
                };
                var config = DurableConfig.builder()
                        .withExecutorService(workers)
                        .withDurableExecutionClient(new LocalMemoryExecutionClient())
                        .withCheckpointDelay(Duration.ZERO)
                        .withPlugins(scoped, legacy)
                        .build();
                var result = callers.submit(() -> DurableExecutor.execute(
                        input(marker),
                        null,
                        TypeToken.get(String.class),
                        (value, context) -> {
                            context.wait("pause", Duration.ofSeconds(1));
                            return "done";
                        },
                        config));
                try {
                    assertTrue(scoped.closeEntered.await(3, TimeUnit.SECONDS));
                    assertThrows(
                            TimeoutException.class,
                            () -> result.get(600, TimeUnit.MILLISECONDS),
                            "a blocked end hook has no fallback to the caller thread");
                    scoped.releaseClose.countDown();
                    assertEquals(
                            ExecutionStatus.PENDING,
                            result.get(5, TimeUnit.SECONDS).status());
                    assertSame(owner.get(), endThread.get());
                    assertEquals(marker, observed.get());
                    assertNull(previous.get(), "The prior invocation's end hook must clear the reused worker");
                    assertNull(workers.submit(local::get).get(3, TimeUnit.SECONDS));
                } finally {
                    scoped.releaseClose.countDown();
                }
            }
        } finally {
            callers.shutdownNow();
            workers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static final class PausingOtelPlugin extends InvocationOtelPlugin {
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        private PausingOtelPlugin() {
            super(
                    SdkTracerProvider.builder(),
                    OtelPluginConfig.builder().enableMdc(false).build());
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            try {
                super.onInvocationEnd(info);
            } finally {
                closeEntered.countDown();
                await(releaseClose);
            }
        }
    }

    private record Observation(
            Thread startThread, Thread endThread, String endValue, String workerAfter, String previous, int endCalls) {}

    private static void awaitCallerJoin(Thread caller) {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            if (caller.getState() == Thread.State.WAITING
                    && Arrays.stream(caller.getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals(CompletableFuture.class.getName())
                                    && frame.getMethodName().equals("join"))) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("invocation caller did not reach its future join before releasing the handler");
    }

    private static DurableExecutionInput input(String marker) {
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/" + marker + "/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
