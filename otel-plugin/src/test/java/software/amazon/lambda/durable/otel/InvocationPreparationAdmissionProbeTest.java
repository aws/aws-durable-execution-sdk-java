// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.*;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

/** Diagnostic only: distinguishes new admission from rewriting an already-selected outcome. */
class InvocationPreparationAdmissionProbeTest {
    @ParameterizedTest
    @CsvSource({
        "unawaited,false",
        "unawaited,true",
        "early-parallel,false",
        "early-parallel,true",
        "awaited,false",
        "awaited,true"
    })
    void preparationAdmissionWindow(String mode, boolean rejectResume) throws Exception {
        var pending = new CountDownLatch(1);
        var preparationEntered = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);
        var conditionFinished = new CountDownLatch(1);
        var keeperEntered = new CountDownLatch(1);
        var releaseKeeper = new CountDownLatch(1);
        var backendReleased = new AtomicBoolean();
        var preparing = new AtomicBoolean();
        var predicates = new AtomicInteger();
        var preparationDispatches = new AtomicInteger();
        var childFailure = new AtomicReference<Throwable>();
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var rejection = new RejectedExecutionException("controlled READY admission rejection");
        var workers =
                new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>()) {
                    @Override
                    public void execute(Runnable task) {
                        if (backendReleased.get()) {
                            if (preparing.get()) preparationDispatches.incrementAndGet();
                            if (rejectResume) throw rejection;
                        }
                        super.execute(task);
                    }
                };
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                var result = super.checkpoint(arn, token, updates);
                var op = getOperationByName("condition");
                if (op != null && op.status() == OperationStatus.PENDING) pending.countDown();
                return result;
            }
        };
        var serializer = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                if ("selected-root".equals(value)) {
                    preparing.set(true);
                    preparationEntered.countDown();
                    await(releasePreparation);
                }
                return delegate.serialize(value);
            }

            public <T> T deserialize(String value, TypeToken<T> type) {
                return delegate.deserialize(value, type);
            }
        };
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(client)
                .withSerDes(serializer)
                .withCheckpointDelay(Duration.ZERO)
                .withPollingStrategy(attempt -> Duration.ofMillis(1))
                .withPlugins(new DurableExecutionPlugin() {
                    public void onInvocationEnd(InvocationEndInfo info) {
                        ends.add(info);
                    }
                })
                .build();
        var caller = Executors.newSingleThreadExecutor();
        try {
            var call = caller.submit(() -> DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, root) -> {
                        if (mode.equals("awaited")) {
                            root.runInChildContextAsync("keep-active", String.class, child -> {
                                keeperEntered.countDown();
                                await(releaseKeeper);
                                return "keeper";
                            });
                            await(keeperEntered);
                            try {
                                condition(root, predicates, childFailure, conditionFinished);
                            } finally {
                                releaseKeeper.countDown();
                            }
                        } else if (mode.equals("early-parallel")) {
                            var parallel = root.parallel(
                                    "early",
                                    ParallelConfig.builder()
                                            .completionConfig(CompletionConfig.minSuccessful(1))
                                            .build());
                            try (parallel) {
                                parallel.branch(
                                        "waiting",
                                        Integer.class,
                                        child -> condition(child, predicates, childFailure, conditionFinished));
                                parallel.branch("winner", String.class, child -> {
                                    await(pending);
                                    return "winner";
                                });
                            }
                            assertEquals(1, parallel.get().succeeded());
                        } else {
                            root.runInChildContextAsync(
                                    "unawaited",
                                    Integer.class,
                                    child -> condition(child, predicates, childFailure, conditionFinished));
                            await(pending);
                        }
                        return "selected-root";
                    },
                    config));
            if (!pending.await(5, TimeUnit.SECONDS)) {
                System.out.println("PROBE_SETUP mode=" + mode + " done=" + call.isDone());
                if (call.isDone()) System.out.println("PROBE_EARLY_RESULT " + call.get());
                fail("Real backend did not reach PENDING");
            }
            if (!mode.equals("awaited"))
                assertTrue(
                        preparationEntered.await(5, TimeUnit.SECONDS),
                        "Root success selected before real output serializer");
            backendReleased.set(true);
            client.advanceTime();
            assertTrue(conditionFinished.await(5, TimeUnit.SECONDS), "Real durable waiter settled after backend READY");
            releasePreparation.countDown();
            releaseKeeper.countDown();
            if (mode.equals("awaited") && rejectResume) {
                var thrown = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
                assertInstanceOf(UnrecoverableDurableExecutionException.class, thrown.getCause());
                assertSame(rejection, thrown.getCause().getCause());
                assertEquals(InvocationStatus.RETRYING, ends.get(0).invocationStatus());
            } else {
                assertEquals(
                        ExecutionStatus.SUCCEEDED, call.get(5, TimeUnit.SECONDS).status());
                assertEquals(InvocationStatus.SUCCEEDED, ends.get(0).invocationStatus());
            }
            assertEquals(1, ends.size());
            System.out.println("PREPARATION_ADMISSION mode=" + mode + " reject=" + rejectResume
                    + " dispatchesWhilePreparing=" + preparationDispatches.get()
                    + " predicates=" + predicates.get() + " childFailure="
                    + (childFailure.get() == null
                            ? "none"
                            : childFailure.get().getClass().getName())
                    + " originalRejectionCause="
                    + (childFailure.get() != null && childFailure.get().getCause() == rejection)
                    + " backend=" + client.getOperationByName("condition").status()
                    + " End=" + ends.get(0).invocationStatus());
            if (!mode.equals("awaited")) {
                // Proposed admission guarantee, distinct from replacing the selected SUCCEEDED outcome.
                assertEquals(0, preparationDispatches.get(), "No new READY user-worker admission after root selection");
                assertEquals(1, predicates.get());
            } else assertEquals(rejectResume ? 1 : 2, predicates.get());
        } finally {
            releasePreparation.countDown();
            releaseKeeper.countDown();
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void successfulOversizedCheckpointStillRunsAfterAdmissionCloses() throws Exception {
        var client = new LocalMemoryExecutionClient();
        var workers = Executors.newCachedThreadPool();
        var caller = Executors.newSingleThreadExecutor();
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var payload = "x".repeat(6 * 1024 * 1024);
        try {
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(client)
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPlugins(new DurableExecutionPlugin() {
                        public void onInvocationEnd(InvocationEndInfo info) {
                            ends.add(info);
                        }
                    })
                    .build();
            var result = caller.submit(() -> DurableExecutor.execute(
                            input(), null, TypeToken.get(String.class), (value, context) -> payload, config))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.SUCCEEDED, result.status());
            assertEquals("", result.result());
            var updates = client.getOperationUpdates().stream()
                    .filter(x -> x.type() == OperationType.EXECUTION)
                    .toList();
            assertEquals(1, updates.size());
            assertEquals(OperationAction.SUCCEED, updates.get(0).action());
            assertEquals(new JacksonSerDes().serialize(payload), updates.get(0).payload());
            assertEquals(1, ends.size());
            assertEquals(InvocationStatus.SUCCEEDED, ends.get(0).invocationStatus());
        } finally {
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static int condition(
            DurableContext context,
            AtomicInteger predicates,
            AtomicReference<Throwable> failure,
            CountDownLatch finished) {
        try {
            return context.waitForCondition(
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
        } catch (RuntimeException | Error thrown) {
            failure.set(thrown);
            throw thrown;
        } finally {
            finished.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(8, TimeUnit.SECONDS), "Probe gate released within bounded cleanup");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/preparation/execution",
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
