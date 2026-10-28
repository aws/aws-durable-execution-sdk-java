// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class OwnedExecutorMdcFailureTest {
    enum Cleanup {
        NONE,
        ORDINARY,
        FATAL,
        WRAPPED_FATAL,
        SAME_FATAL,
        WRAPPED_SAME_FATAL
    }

    static Stream<Arguments> cases() {
        return Stream.concat(
                Stream.of(false, true)
                        .flatMap(death ->
                                Stream.of(Cleanup.values()).map(cleanup -> Arguments.of(death, cleanup, false))),
                Stream.of(false, true).map(death -> Arguments.of(death, Cleanup.NONE, true)));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("cases")
    void asynchronousMdcFatalRetainsLaterOwnedCleanup(boolean death, Cleanup cleanup, boolean shared) throws Exception {
        Error first = death ? new ThreadDeath() : new InternalError("MDC restoration fatal");
        var laterFatal = new InternalError("later owned shutdown fatal");
        var ordinary = new IllegalStateException("later owned shutdown diagnostic");
        Throwable cleanupFailure = switch (cleanup) {
            case NONE -> null;
            case ORDINARY -> ordinary;
            case FATAL -> laterFatal;
            case WRAPPED_FATAL -> new CompletionException(laterFatal);
            case SAME_FATAL -> first;
            case WRAPPED_SAME_FATAL -> new CompletionException(first);
        };
        var end = new AtomicReference<InvocationEndInfo>();
        var owner = new AtomicReference<Thread>();
        var ends = new AtomicInteger();
        var restored = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var manager = new AtomicReference<ExecutionManager>();
        var fatalSeenByShutdown = new AtomicReference<Error>();
        var workerFailure = new AtomicReference<Throwable>();
        var workerEscaped = new CountDownLatch(1);
        var originalAdapter = MDC.getMDCAdapter();
        var adapterField = MDC.class.getDeclaredField("MDC_ADAPTER");
        adapterField.setAccessible(true);
        adapterField.set(null, new BasicMDCAdapter() {
            @Override
            public void setContextMap(Map<String, String> values) {
                if (Thread.currentThread() == owner.get()
                        && end.get() != null
                        && values.equals(Map.of("seed", "before-invocation"))) {
                    restored.incrementAndGet();
                    throw first;
                }
                super.setContextMap(values);
            }
        });
        var pool =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), task -> {
                    var thread = new Thread(
                            () -> {
                                MDC.put("seed", "before-invocation");
                                task.run();
                            },
                            "mdc-restoration-owner");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        workerFailure.set(failure);
                        workerEscaped.countDown();
                    });
                    return thread;
                }) {
                    @Override
                    public void shutdown() {
                        shutdowns.incrementAndGet();
                        fatalSeenByShutdown.set(manager.get().getInvocationFatal());
                        super.shutdown();
                        if (cleanupFailure != null) ExceptionHelper.sneakyThrow(cleanupFailure);
                    }
                };
        pool.prestartAllCoreThreads();
        var builder = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    public void onInvocationStart(InvocationInfo info) {
                        owner.set(Thread.currentThread());
                    }

                    public void onInvocationEnd(InvocationEndInfo info) {
                        ends.incrementAndGet();
                        end.set(info);
                    }
                });
        if (shared) builder.withExecutorService(pool);
        else builder.withInvocationExecutorFactory(() -> pool);
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                manager.set(((DurableContextImpl) context).getExecutionManager());
                                return value;
                            },
                            builder.build()));
            assertSame(first, observed, "The first root completion fatal must reach the caller unchanged");
            assertTrue(workerEscaped.await(3, TimeUnit.SECONDS));
            assertSame(first, workerFailure.get());
            assertEquals(1, restored.get(), "Only final handler MDC restoration injects the fatal");
            assertEquals(1, ends.get());
            assertEquals(InvocationStatus.SUCCEEDED, end.get().invocationStatus());
            assertNull(end.get().executionError(), "End must keep its already-selected successful body outcome");
            assertEquals(shared ? 0 : 1, shutdowns.get());
            if (shared) assertFalse(pool.isShutdown());
            else assertTrue(pool.isTerminated());
            assertNull(fatalSeenByShutdown.get(), "This regression reaches cleanup through the root future alone");
            if (cleanupFailure != null) {
                var expected = cleanup == Cleanup.ORDINARY
                        ? ordinary
                        : cleanup == Cleanup.SAME_FATAL || cleanup == Cleanup.WRAPPED_SAME_FATAL ? first : laterFatal;
                assertTrue(contains(observed, expected), "Retain the later owned cleanup diagnostic");
            }
            assertNoCycle(observed, Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));
        } finally {
            try {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                adapterField.set(null, originalAdapter);
            }
        }
    }

    private static boolean contains(Throwable root, Throwable expected) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            var failure = pending.remove();
            if (failure == expected) return true;
            if (!seen.add(failure)) continue;
            if (failure.getCause() != null) pending.add(failure.getCause());
            Collections.addAll(pending, failure.getSuppressed());
        }
        return false;
    }

    private static void assertNoCycle(Throwable failure, Set<Throwable> path) {
        assertTrue(path.add(failure), "Diagnostics must not contain a cause/suppression cycle");
        if (failure.getCause() != null) assertNoCycle(failure.getCause(), path);
        for (var suppressed : failure.getSuppressed()) assertNoCycle(suppressed, path);
        path.remove(failure);
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/mdc",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("mdc")
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
