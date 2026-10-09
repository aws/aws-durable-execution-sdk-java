// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
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
class InvocationLateFlushProbeTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void alreadyRunningStepCompletesAfterEnd(boolean earlyParallel) throws Exception {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var lateOperationEnds = new AtomicInteger();
        var bodyCalls = new AtomicInteger();
        var client = new LocalMemoryExecutionClient();
        var workers = Executors.newCachedThreadPool();
        var caller = Executors.newSingleThreadExecutor();
        try (var exporter = InMemorySpanExporter.create();
                var batch = BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofDays(1))
                        .build()) {
            var otel = new InvocationOtelPlugin(
                    SdkTracerProvider.builder().addSpanProcessor(batch),
                    OtelPluginConfig.builder()
                            .contextExtractor(() -> null)
                            .enableMdc(false)
                            .build());
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
                                root.stepAsync("held", String.class, step -> {
                                    bodyCalls.incrementAndGet();
                                    running.countDown();
                                    await(release);
                                    return "completed";
                                });
                                await(running);
                            }
                            return "root-success";
                        },
                        config));
                assertTrue(ended.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> call.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(
                        ExecutionStatus.SUCCEEDED, call.get(5, TimeUnit.SECONDS).status());
                assertEquals(
                        OperationStatus.SUCCEEDED,
                        client.getOperationByName("held").status());
                assertEquals(1, bodyCalls.get());
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
                assertTrue(lateOperationEnds.get() > 0, "Existing public operation-end occurs after invocation End");
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
