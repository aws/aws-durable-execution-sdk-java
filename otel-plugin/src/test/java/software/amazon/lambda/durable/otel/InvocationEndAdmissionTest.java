// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class InvocationEndAdmissionTest {
    @ParameterizedTest
    @ValueSource(strings = {"unawaited", "awaited", "early-parallel", "already-running"})
    void alreadyAdmittedWorkersRetainTheBaseCleanupBoundary(String mode) throws Exception {
        boolean awaitResult = mode.equals("awaited");
        boolean alreadyRunning = mode.equals("already-running");
        var runningEntered = new CountDownLatch(1);
        var conditionStopped = new CountDownLatch(1);
        var releaseRunning = new CountDownLatch(1);
        var nextDispatch = new CountDownLatch(1);
        var releaseDispatch = new CountDownLatch(1);
        var endEntered = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(1);
        var terminal = new CountDownLatch(1);
        var predicates = new AtomicInteger();
        var lateHooks = new AtomicInteger();
        var lateStarts = new AtomicInteger();
        var ending = new AtomicBoolean();
        var snapshotStatus = new AtomicReference<OperationStatus>();
        var firstResume = new AtomicBoolean(true);
        var workers =
                new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>()) {
                    @Override
                    public void execute(Runnable task) {
                        if (Thread.currentThread().getName().startsWith("durable-sdk-internal-")
                                && firstResume.compareAndSet(true, false)) {
                            nextDispatch.countDown();
                            await(releaseDispatch);
                        }
                        super.execute(task);
                    }
                };
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                var applied = super.checkpoint(arn, token, updates);
                advanceTime();
                var ready = super.checkpoint(arn, applied.checkpointToken(), List.of());
                var states = new LinkedHashMap<String, Operation>();
                applied.newExecutionState().operations().forEach(op -> states.put(op.id(), op));
                ready.newExecutionState().operations().forEach(op -> states.put(op.id(), op));
                if (getOperationByName("condition") != null
                        && getOperationByName("condition").status() == OperationStatus.SUCCEEDED) terminal.countDown();
                return ready.toBuilder()
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(states.values())
                                .build())
                        .build();
            }
        };
        var exporter = InMemorySpanExporter.create();
        var otel = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .build());
        var gate = new DurableExecutionPlugin() {
            public void onInvocationEnd(InvocationEndInfo info) {
                snapshotStatus.set(info.operations().values().stream()
                        .filter(op -> "condition".equals(op.name()))
                        .findFirst()
                        .orElseThrow()
                        .status());
                ending.set(true);
                endEntered.countDown();
                await(releaseEnd);
            }

            public void onUserFunctionStart(UserFunctionStartInfo info) {
                if (ending.get()) {
                    lateHooks.incrementAndGet();
                    lateStarts.incrementAndGet();
                }
            }

            public void onUserFunctionEnd(UserFunctionEndInfo info) {
                if (ending.get()) lateHooks.incrementAndGet();
            }

            public void onOperationEnd(OperationEndInfo info) {
                if (ending.get()) lateHooks.incrementAndGet();
            }
        };
        // The merged lifecycle unwinds End in reverse order, so OTel must be registered after the gate.
        // This fixture pauses a continuation after admission. Draining must stop its owner and join the
        // continuation before End. A predicate that already started must finish its checkpoint first.
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO)
                .withPollingStrategy(attempt -> Duration.ZERO)
                .withPlugins(gate, otel)
                .build();
        var caller = Executors.newSingleThreadExecutor();
        try {
            var call = caller.submit(() -> DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        Function<DurableContext, DurableFuture<Integer>> startCondition = branch -> {
                            var condition = branch.waitForConditionAsync(
                                    "condition",
                                    Integer.class,
                                    (state, step) -> {
                                        predicates.incrementAndGet();
                                        if (alreadyRunning && state == 2) {
                                            runningEntered.countDown();
                                            await(releaseRunning);
                                        }
                                        return state == 1
                                                ? WaitForConditionResult.continuePolling(2)
                                                : WaitForConditionResult.stopPolling(state);
                                    },
                                    WaitForConditionConfig.<Integer>builder()
                                            .initialState(1)
                                            .waitStrategy((state, attempt) -> Duration.ofSeconds(1))
                                            .build());
                            ((BaseDurableOperation) condition)
                                    .getCompletionFuture()
                                    .whenComplete((result, failure) -> {
                                        if (failure instanceof SuspendExecutionException) conditionStopped.countDown();
                                    });
                            return condition;
                        };
                        if (mode.equals("early-parallel")) {
                            var parallel = context.parallel(
                                    "early",
                                    ParallelConfig.builder()
                                            .completionConfig(CompletionConfig.minSuccessful(1))
                                            .build());
                            try (parallel) {
                                parallel.branch(
                                        "waiting",
                                        Integer.class,
                                        branch -> startCondition.apply(branch).get());
                                parallel.branch("winner", String.class, branch -> {
                                    await(nextDispatch);
                                    return "winner";
                                });
                            }
                            assertEquals(1, parallel.get().succeeded());
                        } else {
                            var condition = startCondition.apply(context);
                            await(nextDispatch);
                            if (alreadyRunning) await(runningEntered);
                            if (awaitResult) condition.get();
                        }
                        return "root-done";
                    },
                    config));
            assertTrue(nextDispatch.await(3, TimeUnit.SECONDS));
            if (alreadyRunning) {
                releaseDispatch.countDown();
                assertTrue(runningEntered.await(3, TimeUnit.SECONDS));
                assertEquals(1, endEntered.getCount(), "End must wait for the running predicate");
                assertThrows(
                        TimeoutException.class,
                        () -> call.get(100, TimeUnit.MILLISECONDS),
                        "Draining waits for the real handler before End");
                releaseRunning.countDown();
            } else if (!awaitResult) {
                assertThrows(TimeoutException.class, () -> call.get(100, TimeUnit.MILLISECONDS));
                assertEquals(1, endEntered.getCount(), "End must join the admitted continuation");
                assertTrue(conditionStopped.await(3, TimeUnit.SECONDS), "Draining must release the condition owner");
            }
            releaseDispatch.countDown();
            if (awaitResult || alreadyRunning) assertTrue(terminal.await(3, TimeUnit.SECONDS));
            assertTrue(endEntered.await(3, TimeUnit.SECONDS));
            releaseEnd.countDown();
            assertEquals(
                    ExecutionStatus.SUCCEEDED, call.get(3, TimeUnit.SECONDS).status());
            var spans = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("condition"))
                    .map(span ->
                            span.getName() + ":" + span.getAttributes().get(SpanAttributes.DURABLE_OPERATION_STATUS))
                    .toList();
            System.out.println("END_SNAPSHOT_PUBLIC mode=" + mode + " snapshot=" + snapshotStatus.get()
                    + " backend=" + client.getOperationByName("condition").status() + " predicates=" + predicates.get()
                    + " lateHooks=" + lateHooks.get() + " lateStarts=" + lateStarts.get() + " operationSpans=" + spans);
            assertEquals(0, lateStarts.get(), "Drained continuations must not start user functions after End");
            assertEquals(0, lateHooks.get());
            assertEquals(awaitResult || alreadyRunning ? 2 : 1, predicates.get());
            assertEquals(
                    awaitResult || alreadyRunning ? OperationStatus.SUCCEEDED : OperationStatus.READY,
                    snapshotStatus.get());
            assertEquals(
                    snapshotStatus.get(), client.getOperationByName("condition").status());
        } finally {
            releaseRunning.countDown();
            releaseDispatch.countDown();
            releaseEnd.countDown();
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            exporter.close();
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

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/end-boundary/execution",
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
