// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestContext;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class OwnedCallbackFailureAggregationTest {
    enum Stage {
        SHUTDOWN,
        AWAIT,
        STATE,
        FORCED,
        INITIAL,
        SETUP
    }

    enum Wrapper {
        RAW,
        COMPLETION,
        EXECUTION,
        CUSTOM
    }

    record Scenario(
            Stage stage,
            Wrapper wrapper,
            boolean recorded,
            boolean repeatAtState,
            boolean wrappedState,
            boolean death) {}

    static Stream<Scenario> cases() {
        var triple = Stream.of(Wrapper.values())
                .flatMap(wrapper -> Stream.of(false, true)
                        .flatMap(wrappedState -> Stream.of(false, true)
                                .map(death -> new Scenario(Stage.SHUTDOWN, wrapper, true, true, wrappedState, death))));
        var wrappers = Stream.of(Stage.values())
                .filter(stage -> stage != Stage.SETUP)
                .flatMap(stage -> Stream.of(Wrapper.COMPLETION, Wrapper.EXECUTION)
                        .flatMap(wrapper -> (stage == Stage.INITIAL ? Stream.of(false) : Stream.of(false, true))
                                .map(recorded -> new Scenario(stage, wrapper, recorded, false, false, false))));
        return Stream.concat(
                Stream.concat(triple, wrappers),
                Stream.of(
                        new Scenario(Stage.SHUTDOWN, Wrapper.RAW, false, true, false, false),
                        new Scenario(Stage.INITIAL, Wrapper.CUSTOM, false, false, false, false),
                        new Scenario(Stage.SETUP, Wrapper.COMPLETION, true, true, true, false)));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("cases")
    void callbackFailuresKeepTheFirstFatalAndAllNonAliasDiagnostics(Scenario scenario) throws Exception {
        Error first = scenario.death() ? new ThreadDeath() : new InternalError("serializer fatal");
        var later = new InternalError("executor callback fatal");
        var diagnostic = new IllegalArgumentException("wrapper cleanup diagnostic");
        var nestedDiagnostic = new IllegalStateException("nested wrapper diagnostic");
        var queryDiagnostic = new IllegalArgumentException("query wrapper diagnostic");
        var ordinaryShutdown = new IllegalStateException("graceful shutdown failed before delegation");
        var causeReads = new AtomicInteger();
        Throwable callback = switch (scenario.wrapper()) {
            case RAW -> later;
            case COMPLETION -> new CompletionException(later);
            case EXECUTION -> new ExecutionException(later);
            case CUSTOM ->
                new CompletionException(later) {
                    @Override
                    public synchronized Throwable getCause() {
                        assertEquals(1, causeReads.incrementAndGet(), "Read a custom wrapper cause only once");
                        return super.getCause();
                    }
                };
        };
        if (callback != later) {
            callback.addSuppressed(diagnostic);
            var alias = new CompletionException(later);
            alias.addSuppressed(nestedDiagnostic);
            callback.addSuppressed(alias);
            if (scenario.recorded()) callback.addSuppressed(first);
        }
        Throwable repeated = scenario.wrappedState() ? new CompletionException(first) : first;
        if (repeated != first) repeated.addSuppressed(queryDiagnostic);
        var started = new AtomicBoolean();
        var inject = new AtomicBoolean(true);
        var firstAwait = new AtomicBoolean(true);
        var firstInitial = new AtomicBoolean(true);
        var shutdowns = new AtomicInteger();
        var forced = new AtomicInteger();
        var stateFailures = new AtomicInteger();
        var bodies = new AtomicInteger();
        var serializations = new AtomicInteger();
        var manager = new AtomicReference<ExecutionManager>();
        var fatalAtShutdown = new AtomicReference<Error>();
        var ends = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var workerFatal = new AtomicReference<Throwable>();
        var workerEscaped = new CountDownLatch(1);
        var pool =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), task -> {
                    var thread = new Thread(task, "callback-failure-owner");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        workerFatal.set(failure);
                        workerEscaped.countDown();
                    });
                    return thread;
                }) {
                    @Override
                    public void shutdown() {
                        shutdowns.incrementAndGet();
                        started.set(true);
                        if (manager.get() != null)
                            fatalAtShutdown.set(manager.get().getInvocationFatal());
                        if (inject.get() && scenario.stage() == Stage.FORCED) throw ordinaryShutdown;
                        super.shutdown();
                        if (inject.get() && (scenario.stage() == Stage.SHUTDOWN || scenario.stage() == Stage.SETUP))
                            ExceptionHelper.sneakyThrow(callback);
                    }

                    @Override
                    public List<Runnable> shutdownNow() {
                        var tasks = super.shutdownNow();
                        if (inject.get()) {
                            forced.incrementAndGet();
                            if (scenario.stage() == Stage.FORCED) ExceptionHelper.sneakyThrow(callback);
                        }
                        return tasks;
                    }

                    @Override
                    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                        if (inject.get() && scenario.stage() == Stage.AWAIT && firstAwait.compareAndSet(true, false))
                            ExceptionHelper.sneakyThrow(callback);
                        return super.awaitTermination(timeout, unit);
                    }

                    @Override
                    public boolean isShutdown() {
                        if (inject.get()) {
                            if (scenario.stage() == Stage.INITIAL && firstInitial.compareAndSet(true, false)) {
                                stateFailures.incrementAndGet();
                                ExceptionHelper.sneakyThrow(callback);
                            }
                            if (started.get() && (scenario.repeatAtState() || scenario.stage() == Stage.STATE)) {
                                stateFailures.incrementAndGet();
                                ExceptionHelper.sneakyThrow(scenario.repeatAtState() ? repeated : callback);
                            }
                        }
                        return super.isShutdown();
                    }
                };
        pool.prestartAllCoreThreads();
        var serde = new JacksonSerDes();
        var config = DurableConfig.builder()
                .withInvocationExecutorFactory(() -> pool)
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withSerDes(new SerDes() {
                    public String serialize(Object value) {
                        serializations.incrementAndGet();
                        if (scenario.recorded()) throw first;
                        return serde.serialize(value);
                    }

                    public <T> T deserialize(String value, TypeToken<T> type) {
                        return serde.deserialize(value, type);
                    }
                })
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    public void onInvocationEnd(InvocationEndInfo info) {
                        ends.incrementAndGet();
                        end.set(info);
                    }
                })
                .build();
        var requestReads = new AtomicInteger();
        var runtime = scenario.stage() == Stage.SETUP
                ? new TestContext("setup-callback") {
                    @Override
                    public String getAwsRequestId() {
                        if (requestReads.incrementAndGet() == 2) throw first;
                        return super.getAwsRequestId();
                    }
                }
                : null;
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input(),
                            runtime,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                manager.set(((DurableContextImpl) context).getExecutionManager());
                                bodies.incrementAndGet();
                                return "output";
                            },
                            config));
            assertSame(scenario.recorded() ? first : later, observed);
            if (scenario.recorded() && scenario.stage() != Stage.SETUP) {
                assertTrue(workerEscaped.await(3, TimeUnit.SECONDS));
                assertSame(first, workerFatal.get());
                assertSame(first, fatalAtShutdown.get());
            }
            assertTrue(contains(observed, later), "Retain the later executor callback fatal");
            if (scenario.wrapper() != Wrapper.RAW) {
                assertTrue(contains(observed, diagnostic), "Retain discarded wrapper diagnostics");
                assertTrue(contains(observed, nestedDiagnostic), "Retain diagnostics on wrapper aliases");
            }
            if (scenario.wrappedState()) assertTrue(contains(observed, queryDiagnostic));
            if (scenario.stage() == Stage.FORCED) assertTrue(contains(observed, ordinaryShutdown));
            if (scenario.repeatAtState()) {
                assertEquals(1, stateFailures.get());
                assertTrue(contains(observed, first));
            }
            assertEquals(1, shutdowns.get());
            if (scenario.stage() == Stage.INITIAL || scenario.stage() == Stage.SETUP) {
                assertEquals(0, bodies.get());
                assertEquals(0, serializations.get());
                assertEquals(0, ends.get());
            } else {
                assertEquals(1, bodies.get());
                assertEquals(1, serializations.get());
                assertEquals(1, ends.get());
                assertEquals(
                        scenario.recorded() ? InvocationStatus.RETRYING : InvocationStatus.SUCCEEDED,
                        end.get().invocationStatus());
                assertSame(scenario.recorded() ? first : null, end.get().executionError());
            }
            if (scenario.wrapper() == Wrapper.CUSTOM) assertEquals(1, causeReads.get());
            if (scenario.stage() == Stage.SETUP) {
                assertEquals(2, requestReads.get());
                assertNull(fatalAtShutdown.get(), "Setup fatal is carried as originalFailure, without plugin state");
            }
            assertAcyclic(observed, Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));
        } finally {
            inject.set(false);
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static boolean contains(Throwable root, Throwable target) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            var next = pending.remove();
            if (next == target) return true;
            if (!seen.add(next)) continue;
            if (next.getCause() != null) pending.add(next.getCause());
            Collections.addAll(pending, next.getSuppressed());
        }
        return false;
    }

    private static void assertAcyclic(Throwable failure, Set<Throwable> path) {
        assertTrue(path.add(failure), "Executor callback diagnostics must not point back to the first fatal");
        if (failure.getCause() != null) assertAcyclic(failure.getCause(), path);
        for (var diagnostic : failure.getSuppressed()) assertAcyclic(diagnostic, path);
        path.remove(failure);
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/callback-fatal",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("callback-fatal")
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
