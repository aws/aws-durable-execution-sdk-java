// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class RecordedFatalCleanupTest {
    enum Cleanup {
        NONE,
        ORDINARY,
        FATAL,
        WRAPPED_FATAL,
        SAME_FATAL,
        WRAPPED_SAME_FATAL
    }

    static Stream<Arguments> cases() {
        return Stream.of(Cleanup.values())
                .flatMap(cleanup -> Stream.of(false, true)
                        .flatMap(
                                plugins -> Stream.of(false, true).map(death -> Arguments.of(cleanup, plugins, death))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("cases")
    void recordedSerializerFatalOwnsCleanupDiagnosticsWithoutCycles(Cleanup cleanup, boolean plugins, boolean death)
            throws Exception {
        Error first = death ? new ThreadDeath() : new InternalError("fatal output serialization");
        var ordinary = new IllegalStateException("ordinary owned shutdown failure");
        var laterFatal = new InternalError("later owned shutdown fatal");
        Throwable shutdownFailure = switch (cleanup) {
            case NONE -> null;
            case ORDINARY -> ordinary;
            case FATAL -> laterFatal;
            case WRAPPED_FATAL -> new CompletionException(laterFatal);
            case SAME_FATAL -> first;
            case WRAPPED_SAME_FATAL -> new CompletionException(first);
        };
        var bodies = new AtomicInteger();
        var serialized = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var ends = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var manager = new AtomicReference<ExecutionManager>();
        var fatalAtShutdown = new AtomicReference<Error>();
        var workerFatal = new AtomicReference<Throwable>();
        var workerEscaped = new CountDownLatch(1);
        var pool =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), task -> {
                    var worker = new Thread(task, "recorded-fatal-owner");
                    worker.setUncaughtExceptionHandler((ignored, failure) -> {
                        workerFatal.set(failure);
                        workerEscaped.countDown();
                    });
                    return worker;
                }) {
                    @Override
                    public void shutdown() {
                        shutdowns.incrementAndGet();
                        fatalAtShutdown.set(manager.get().getInvocationFatal());
                        super.shutdown();
                        if (shutdownFailure != null) ExceptionHelper.sneakyThrow(shutdownFailure);
                    }
                };
        pool.prestartAllCoreThreads();
        var builder = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> pool)
                .withSerDes(new SerDes() {
                    public String serialize(Object value) {
                        serialized.incrementAndGet();
                        throw first;
                    }

                    @SuppressWarnings("unchecked")
                    public <T> T deserialize(String value, TypeToken<T> type) {
                        return (T) "input";
                    }
                });
        if (plugins)
            builder.withPlugins(ignored -> new DurableExecutionPlugin() {
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.incrementAndGet();
                    end.set(info);
                }
            });
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                manager.set(((DurableContextImpl) context).getExecutionManager());
                                bodies.incrementAndGet();
                                return "output";
                            },
                            builder.build()));
            assertSame(first, observed, "The first recorded fatal must remain the caller outcome");
            assertTrue(workerEscaped.await(3, TimeUnit.SECONDS));
            assertSame(first, workerFatal.get());
            assertSame(first, fatalAtShutdown.get(), "This fatal was recorded before owned executor cleanup");
            assertEquals(1, bodies.get());
            assertEquals(1, serialized.get());
            assertEquals(1, shutdowns.get());
            assertTrue(pool.isTerminated());
            assertEquals(plugins ? 1 : 0, ends.get());
            if (plugins) {
                assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
                assertSame(first, end.get().executionError());
            }
            if (cleanup == Cleanup.ORDINARY)
                assertTrue(contains(first, ordinary), "Retain the ordinary shutdown diagnostic on the recorded fatal");
            if (cleanup == Cleanup.FATAL || cleanup == Cleanup.WRAPPED_FATAL)
                assertTrue(contains(first, laterFatal), "Retain the later cleanup fatal");
            assertAcyclic(first, Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static boolean contains(Throwable root, Throwable expected) {
        var queue = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        queue.add(root);
        while (!queue.isEmpty()) {
            var next = queue.remove();
            if (next == expected) return true;
            if (!seen.add(next)) continue;
            if (next.getCause() != null) queue.add(next.getCause());
            Collections.addAll(queue, next.getSuppressed());
        }
        return false;
    }

    private static void assertAcyclic(Throwable failure, Set<Throwable> path) {
        assertTrue(path.add(failure), "Cleanup diagnostics must not point back to the selected fatal");
        if (failure.getCause() != null) assertAcyclic(failure.getCause(), path);
        for (var additional : failure.getSuppressed()) assertAcyclic(additional, path);
        path.remove(failure);
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/recorded-fatal",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("recorded-fatal")
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
