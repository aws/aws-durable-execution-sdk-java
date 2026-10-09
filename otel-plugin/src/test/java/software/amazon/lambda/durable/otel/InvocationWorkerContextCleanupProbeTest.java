// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

/** Diagnostic for the pre-existing End/close flush boundary; no proposed production policy in this class. */
class InvocationWorkerContextCleanupProbeTest {
    @ParameterizedTest
    @CsvSource({
        "unawaited,false",
        "early-parallel,false",
        "awaited,false",
        "unawaited,true",
        "early-parallel,true",
        "awaited,true"
    })
    void alreadyRunningStepCompletesAfterEnd(String mode, boolean executionView) throws Exception {
        boolean earlyParallel = mode.equals("early-parallel");
        boolean awaited = mode.equals("awaited");
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var lateOperationEnds = new AtomicInteger();
        var bodyCalls = new AtomicInteger();
        var client = new LocalMemoryExecutionClient();
        var owner = new AtomicReference<Thread>();
        var initial = new ConcurrentHashMap<Thread, Context>();
        var workerContext = new AtomicReference<Context>();
        var captured = new AtomicBoolean();
        var retired = new CountDownLatch(1);
        var workers =
                new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>()) {
                    protected void beforeExecute(Thread thread, Runnable task) {
                        initial.put(thread, Context.current());
                    }

                    protected void afterExecute(Runnable task, Throwable failure) {
                        if (Thread.currentThread() == owner.get() && captured.compareAndSet(false, true)) {
                            workerContext.set(Context.current());
                            retired.countDown();
                        }
                    }
                };
        var caller = Executors.newSingleThreadExecutor();
        try (var exporter = InMemorySpanExporter.create();
                var batch = BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofDays(1))
                        .build()) {
            var builder = SdkTracerProvider.builder().addSpanProcessor(batch);
            var pluginConfig = OtelPluginConfig.builder()
                    .contextExtractor(() -> null)
                    .enableMdc(false)
                    .build();
            DurableExecutionPlugin otel = executionView
                    ? new ExecutionOtelPlugin(builder, pluginConfig)
                    : new InvocationOtelPlugin(builder, pluginConfig);
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(client)
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPlugins(otel, new DurableExecutionPlugin() {
                        public void onInvocationEnd(InvocationEndInfo info) {
                            ended.countDown();
                        }

                        public void onOperationEnd(OperationEndInfo info) {
                            if (ended.getCount() == 0) lateOperationEnds.incrementAndGet();
                        }
                    })
                    .build();
            try {
                var call = caller.submit(() -> DurableExecutor.execute(
                        input(),
                        null,
                        TypeToken.get(String.class),
                        (value, root) -> {
                            if (earlyParallel) {
                                var parallel = root.parallel(
                                        "early",
                                        ParallelConfig.builder()
                                                .completionConfig(CompletionConfig.minSuccessful(1))
                                                .build());
                                try (parallel) {
                                    parallel.branch(
                                            "waiting",
                                            String.class,
                                            child -> child.step("held", String.class, step -> {
                                                owner.set(Thread.currentThread());
                                                bodyCalls.incrementAndGet();
                                                running.countDown();
                                                await(release);
                                                return "completed";
                                            }));
                                    parallel.branch("winner", String.class, child -> {
                                        await(running);
                                        return "winner";
                                    });
                                }
                                assertEquals(1, parallel.get().succeeded());
                            } else {
                                var future = root.stepAsync("held", String.class, step -> {
                                    owner.set(Thread.currentThread());
                                    bodyCalls.incrementAndGet();
                                    running.countDown();
                                    await(release);
                                    return "completed";
                                });
                                await(running);
                                if (awaited) future.get();
                            }
                            return "root-success";
                        },
                        config));
                if (awaited) {
                    assertTrue(running.await(5, TimeUnit.SECONDS));
                    release.countDown();
                }
                assertTrue(ended.await(5, TimeUnit.SECONDS));
                if (!awaited) assertThrows(TimeoutException.class, () -> call.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(
                        ExecutionStatus.SUCCEEDED, call.get(5, TimeUnit.SECONDS).status());
                assertEquals(
                        OperationStatus.SUCCEEDED,
                        client.getOperationByName("held").status());
                assertEquals(1, bodyCalls.get());
                assertTrue(retired.await(5, TimeUnit.SECONDS));
                var before = exporter.getFinishedSpanItems().stream()
                        .filter(x -> x.getName().equals("held"))
                        .map(x -> x.getAttributes().get(SpanAttributes.DURABLE_OPERATION_STATUS))
                        .toList();
                batch.forceFlush().join(3, TimeUnit.SECONDS);
                var after = exporter.getFinishedSpanItems().stream()
                        .filter(x -> x.getName().equals("held"))
                        .map(x -> x.getAttributes().get(SpanAttributes.DURABLE_OPERATION_STATUS))
                        .toList();
                System.out.println("LATE_FLUSH_PUBLIC earlyParallel=" + earlyParallel + " backend=SUCCEEDED bodyCalls="
                        + bodyCalls.get() + " lateOperationEnds=" + lateOperationEnds.get() + " beforeManualFlush="
                        + before + " afterManualFlush=" + after);
                if (!awaited)
                    assertTrue(
                            lateOperationEnds.get() > 0, "Existing public operation-end occurs after invocation End");
                System.out.println("WORKER_CONTEXT_PUBLIC executionView=" + executionView + " mode=" + mode
                        + " restored=" + (initial.get(owner.get()) == workerContext.get()) + " remainingSpan="
                        + Span.fromContext(workerContext.get()).getSpanContext());
                assertSame(
                        initial.get(owner.get()),
                        workerContext.get(),
                        "Real worker ambient Context restored after its task finishes");
                // This diagnostic records actual export behavior; it does not redefine which outcome wins.
            } finally {
                release.countDown();
                caller.shutdownNow();
                workers.shutdownNow();
                assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainedPluginAndWorkerRestoreApplicationAmbientOnReuse(boolean executionView) throws Exception {
        var key = ContextKey.<String>named("application-worker");
        var ambient = Context.root().with(key, "retained-application-context");
        var rootExecutor = Executors.newSingleThreadExecutor();
        var nextRoot = new AtomicBoolean(true);
        var retired = new AtomicReference<CountDownLatch>();
        var before = new AtomicReference<Context>();
        var after = new AtomicReference<Context>();
        var actualWorker = new AtomicReference<Thread>();
        var workerExecutor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<Runnable>(),
                        task -> new Thread(
                                () -> {
                                    try (var ignored = ambient.makeCurrent()) {
                                        task.run();
                                    }
                                },
                                "owned-operation-worker")) {
                    protected void beforeExecute(Thread thread, Runnable task) {
                        actualWorker.set(thread);
                        before.set(Context.current());
                    }

                    protected void afterExecute(Runnable task, Throwable failure) {
                        after.set(Context.current());
                        retired.get().countDown();
                    }
                };
        var dispatch = new AbstractExecutorService() {
            public void execute(Runnable task) {
                (nextRoot.compareAndSet(true, false) ? rootExecutor : workerExecutor).execute(task);
            }

            public void shutdown() {
                rootExecutor.shutdown();
                workerExecutor.shutdown();
            }

            public List<Runnable> shutdownNow() {
                var pending = new ArrayList<>(rootExecutor.shutdownNow());
                pending.addAll(workerExecutor.shutdownNow());
                return pending;
            }

            public boolean isShutdown() {
                return rootExecutor.isShutdown() && workerExecutor.isShutdown();
            }

            public boolean isTerminated() {
                return rootExecutor.isTerminated() && workerExecutor.isTerminated();
            }

            public boolean awaitTermination(long duration, TimeUnit unit) throws InterruptedException {
                return rootExecutor.awaitTermination(duration, unit) && workerExecutor.awaitTermination(duration, unit);
            }
        };
        var caller = Executors.newSingleThreadExecutor();
        try (var exporter = InMemorySpanExporter.create();
                var batch = BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofDays(1))
                        .build()) {
            var builder = SdkTracerProvider.builder().addSpanProcessor(batch);
            var pluginConfig = OtelPluginConfig.builder()
                    .contextExtractor(() -> null)
                    .enableMdc(false)
                    .build();
            DurableExecutionPlugin otel = executionView
                    ? new ExecutionOtelPlugin(builder, pluginConfig)
                    : new InvocationOtelPlugin(builder, pluginConfig);
            Thread firstWorker = null;
            for (var round = 0; round < 2; round++) {
                var running = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var ended = new CountDownLatch(1);
                retired.set(new CountDownLatch(1));
                nextRoot.set(true);
                var config = DurableConfig.builder()
                        .withExecutorService(dispatch)
                        .withDurableExecutionClient(new LocalMemoryExecutionClient())
                        .withCheckpointDelay(Duration.ZERO)
                        .withPlugins(otel, new DurableExecutionPlugin() {
                            public void onInvocationEnd(InvocationEndInfo info) {
                                ended.countDown();
                            }
                        })
                        .build();
                try {
                    var call = caller.submit(() -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, root) -> {
                                root.stepAsync("held", String.class, step -> {
                                    assertEquals(
                                            "retained-application-context",
                                            Context.current().get(key));
                                    assertTrue(Span.current().getSpanContext().isValid());
                                    running.countDown();
                                    await(release);
                                    return "done";
                                });
                                await(running);
                                return "root-success";
                            },
                            config));
                    assertTrue(ended.await(5, TimeUnit.SECONDS));
                    release.countDown();
                    assertEquals(
                            ExecutionStatus.SUCCEEDED,
                            call.get(5, TimeUnit.SECONDS).status());
                    assertTrue(retired.get().await(5, TimeUnit.SECONDS));
                    assertSame(ambient, before.get());
                    assertSame(ambient, after.get());
                    if (firstWorker == null) firstWorker = actualWorker.get();
                    else assertSame(firstWorker, actualWorker.get(), "The same actual worker was reused");
                    System.out.println("WORKER_REUSE_PUBLIC executionView=" + executionView + " round=" + round
                            + " ambientRestored=true");
                } finally {
                    release.countDown();
                }
            }
        } finally {
            caller.shutdownNow();
            dispatch.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(dispatch.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(8, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/late-flush/execution",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("execution")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
    }
}
