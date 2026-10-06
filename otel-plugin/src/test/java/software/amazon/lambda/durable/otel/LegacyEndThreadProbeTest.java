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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

/** Identical public-API timing probe run against main and the candidate. */
class LegacyEndThreadProbeTest {
    @ParameterizedTest
    @CsvSource({
        "false,false,false", "true,false,false", "false,true,false", "true,true,false",
        "false,false,true", "true,false,true", "false,true,true", "true,true,true"
    })
    void legacyEndThread(boolean mixed, boolean precompleted, boolean pending) throws Exception {
        var local = new ThreadLocal<String>();
        var observations = new CopyOnWriteArrayList<String>();
        var endValues = new ArrayList<String>();
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
                var legacy = new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationStart(InvocationInfo info) {
                        startThread.set(Thread.currentThread());
                        previous.set(local.get());
                        local.set(marker);
                    }

                    @Override
                    public void onInvocationEnd(InvocationEndInfo info) {
                        endThread.set(Thread.currentThread());
                        endValue.set(local.get());
                        local.remove();
                    }
                };
                var plugins = new ArrayList<DurableExecutionPluginFactory>();
                plugins.add(info -> legacy);
                if (mixed)
                    plugins.add(InvocationOtelPlugin.factory(
                            SdkTracerProvider.builder(),
                            OtelPluginConfig.builder().enableMdc(false).build()));
                var config = DurableConfig.builder()
                        .withExecutorService(workers)
                        .withDurableExecutionClient(new LocalMemoryExecutionClient())
                        .withCheckpointDelay(Duration.ZERO)
                        .withPlugins(plugins.toArray(DurableExecutionPluginFactory[]::new))
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
                    observations.add(
                            "round=" + round + " start=" + startThread.get().getName()
                                    + " end=" + endThread.get().getName() + " endValue=" + endValue.get()
                                    + " workerAfter=" + after + " priorWorker=" + previous.get());
                    endValues.add(endValue.get());
                } finally {
                    release.countDown();
                }
            }
            System.out.println("LEGACY_END_TRACE mixed=" + mixed + " precompleted=" + precompleted + " pending="
                    + pending + " " + observations);
            // This controlled success path attaches the legacy continuation before the handler completes.
            // Precompleted and suspension paths are measured separately, without claiming owner-thread guarantees.
            if (!precompleted && !pending) assertEquals(List.of("inv1", "inv2"), endValues);
        } finally {
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

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
