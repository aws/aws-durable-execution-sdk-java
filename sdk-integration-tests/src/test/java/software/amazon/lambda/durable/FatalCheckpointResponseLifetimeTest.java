// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import java.util.stream.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.execution.*;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class FatalCheckpointResponseLifetimeTest {
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/lifetime/execution";

    static Stream<Arguments> fatalCases() {
        return Stream.of("checkpoint", "page")
                .flatMap(stage -> Stream.of(false, true)
                        .flatMap(holdEnd -> Stream.of(false, true).map(death -> Arguments.of(stage, holdEnd, death))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("fatalCases")
    void fatalDrainDiscardsAbortedResponseBeforeEndAndKeepsCommittedReplay(String stage, boolean holdEnd, boolean death)
            throws Exception {
        Error fatal = death ? new ThreadDeath() : new InternalError("simulated peer fatal");
        try (var run = new Run(stage, holdEnd, fatal)) {
            var response = run.caller.submit(() -> DurableExecutor.execute(
                    input(List.of()), null, TypeToken.get(String.class), run.handler, run.config));
            await(run.client.ready);
            assertThrows(TimeoutException.class, () -> response.get(700, TimeUnit.MILLISECONDS));
            assertEquals(1, run.endEntered.getCount(), "End cannot precede admitted transport settlement");
            var before = snapshot(run.manager.get());
            assertEquals(
                    OperationStatus.SUCCEEDED,
                    run.client.getOperationByName("saved").status(),
                    "The real local backend persisted the successful side effect before the response gate");
            assertSame(
                    fatal,
                    ExceptionHelper.unwrapAsyncFailure(assertThrows(
                            CompletionException.class, () -> run.poller.get().join())));
            run.client.release.countDown();
            await(run.client.transportExited);
            await(run.endEntered);
            assertSame(fatal, run.client.transportFailure.get());
            assertEquals(before, snapshot(run.manager.get()), "An aborted invocation must not publish a late response");
            assertEquals(0, run.hooksAfterEnd.get(), "No new checkpoint lifecycle hook may run after End starts");
            assertEquals(0, run.pollerSuccess.get());
            assertEquals(0, run.client.pageTwoCalls.get(), "A fatal page return must not fetch another page");
            run.releaseEnd.countDown();
            assertSame(
                    fatal,
                    ExceptionHelper.unwrapAsyncFailure(
                            assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS))));
            assertEquals(1, run.ends.get());
            assertEquals(1, run.effects.get());
            run.manager.get().getPluginRunner().onInvocationEnd(null);
            assertEquals(1, run.ends.get(), "Released plugins cannot receive another End");
            // The next public invocation consumes persisted state; the completed side effect is not repeated.
            run.fault.set(false);
            var replay = DurableExecutor.execute(
                    input(run.client.getAllOperations()), null, TypeToken.get(String.class), run.handler, run.config);
            assertEquals(ExecutionStatus.SUCCEEDED, replay.status());
            assertEquals("\"saved-value\"", replay.result());
            assertEquals(1, run.effects.get());
            assertEquals(1, run.triggerBodies.get());
            assertEquals(2, run.ends.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkpoint", "page"})
    void ordinaryInFlightResponseStillPublishesAndCompletesPollers(String stage) throws Exception {
        try (var run = new Run(stage, false, null)) {
            var response = run.caller.submit(() -> DurableExecutor.execute(
                    input(List.of()), null, TypeToken.get(String.class), run.handler, run.config));
            await(run.client.ready);
            assertThrows(TimeoutException.class, () -> response.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, run.ends.get());
            run.client.release.countDown();
            assertEquals(
                    ExecutionStatus.SUCCEEDED, response.get(3, TimeUnit.SECONDS).status());
            assertEquals(OperationStatus.SUCCEEDED, run.poller.get().join().status());
            assertEquals(1, run.pollerSuccess.get());
            assertEquals(stage.equals("page") ? 1 : 0, run.client.pageTwoCalls.get());
            assertEquals(1, run.effects.get());
            assertEquals(1, run.ends.get());
            assertEquals(
                    OperationStatus.SUCCEEDED,
                    snapshot(run.manager.get()).values().stream()
                            .filter(op -> "saved".equals(op.name()))
                            .findFirst()
                            .orElseThrow()
                            .status());
        }
    }

    private static Map<String, Operation> snapshot(ExecutionManager manager) {
        return manager.getOperationsSnapshot().stream().collect(Collectors.toMap(Operation::id, op -> op));
    }

    private static DurableExecutionInput input(List<Operation> persisted) {
        var operations = new ArrayList<Operation>();
        operations.add(Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build());
        operations.addAll(persisted);
        return new DurableExecutionInput(
                ARN,
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operations).build());
    }

    private static final class Run implements AutoCloseable {
        final AtomicBoolean fault;
        final AtomicReference<ExecutionManager> manager = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Operation>> poller = new AtomicReference<>();
        final AtomicInteger pollerSuccess = new AtomicInteger();
        final AtomicInteger effects = new AtomicInteger();
        final AtomicInteger triggerBodies = new AtomicInteger();
        final AtomicInteger ends = new AtomicInteger();
        final AtomicBoolean endStarted = new AtomicBoolean();
        final AtomicInteger hooksAfterEnd = new AtomicInteger();
        final CountDownLatch endEntered = new CountDownLatch(1);
        final CountDownLatch releaseEnd = new CountDownLatch(1);
        final ExecutorService workers = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "publication-user-owner");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((owner, failure) -> {});
            return thread;
        });
        final ExecutorService caller = Executors.newSingleThreadExecutor();
        final GatedClient client;
        final DurableConfig config;
        final BiFunction<String, DurableContext, String> handler;

        Run(String stage, boolean holdEnd, Error fatal) {
            fault = new AtomicBoolean(fatal != null);
            client = new GatedClient(stage, fatal);
            config = DurableConfig.builder()
                    .withDurableExecutionClient(client)
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPollingStrategy(attempt -> Duration.ZERO)
                    .withPlugins(info -> {
                        endStarted.set(false);
                        return new DurableExecutionPlugin() {
                            public void onUserFunctionStart(UserFunctionStartInfo start) {
                                if (fault.get() && "trigger".equals(start.name())) throw fatal;
                            }

                            public void onOperationChange(OperationChangeInfo change) {
                                if (endStarted.get()) hooksAfterEnd.incrementAndGet();
                            }

                            public void onOperationEnd(OperationEndInfo end) {
                                if (endStarted.get()) hooksAfterEnd.incrementAndGet();
                            }

                            public void onInvocationEnd(InvocationEndInfo end) {
                                endStarted.set(true);
                                ends.incrementAndGet();
                                endEntered.countDown();
                                if (holdEnd && fault.get()) await(releaseEnd);
                            }
                        };
                    })
                    .build();
            handler = (input, context) -> {
                var actual = ((BaseContextImpl) context).getExecutionManager();
                manager.compareAndSet(null, actual);
                var saved = context.stepAsync("saved", String.class, step -> {
                    effects.incrementAndGet();
                    return "saved-value";
                });
                await(client.ready);
                if (poller.get() == null) {
                    var waiting = actual.pollForOperationUpdates(
                            client.getOperationByName("saved").id());
                    waiting.thenAccept(ignored -> pollerSuccess.incrementAndGet());
                    poller.set(waiting);
                }
                context.step("trigger", String.class, step -> {
                    triggerBodies.incrementAndGet();
                    return "allowed";
                });
                return saved.get();
            };
        }

        public void close() throws Exception {
            client.release.countDown();
            releaseEnd.countDown();
            workers.shutdownNow();
            caller.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static final class GatedClient extends LocalMemoryExecutionClient {
        final String stage;
        final Error fatal;
        final AtomicBoolean armed = new AtomicBoolean(true);
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch transportExited = new CountDownLatch(1);
        final AtomicReference<Throwable> transportFailure = new AtomicReference<>();
        final AtomicInteger pageTwoCalls = new AtomicInteger();
        List<Operation> page;

        GatedClient(String stage, Error fatal) {
            this.stage = stage;
            this.fatal = fatal;
        }

        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            var response = super.checkpoint(arn, token, updates);
            if (updates.stream().anyMatch(u -> "saved".equals(u.name()) && u.action() == OperationAction.SUCCEED)
                    && armed.compareAndSet(true, false)) {
                if (stage.equals("checkpoint")) gate();
                else {
                    page = response.newExecutionState().operations();
                    return response.toBuilder()
                            .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                    .operations(List.of())
                                    .nextMarker("first-page")
                                    .build())
                            .build();
                }
            }
            return response;
        }

        public GetDurableExecutionStateResponse getExecutionState(String arn, String token, String marker) {
            if (marker.equals("first-page")) {
                gate();
                return GetDurableExecutionStateResponse.builder()
                        .operations(page)
                        .nextMarker("second-page")
                        .build();
            }
            assertEquals("second-page", marker);
            pageTwoCalls.incrementAndGet();
            return GetDurableExecutionStateResponse.builder()
                    .operations(List.of())
                    .build();
        }

        private void gate() {
            if (fatal != null) {
                var owner = Thread.currentThread();
                var original = owner.getUncaughtExceptionHandler();
                owner.setUncaughtExceptionHandler((thread, failure) -> {
                    transportFailure.set(failure);
                    thread.setUncaughtExceptionHandler(original);
                    transportExited.countDown();
                });
            }
            ready.countDown();
            await(release);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "controlled response gate not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
