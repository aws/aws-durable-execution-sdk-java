// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestResult;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class MdcWorkerCleanupTest {
    private static final String MARKER = "invocation-cleanup-marker";

    static Stream<Arguments> cleanupFatals() {
        return Stream.of(false, true)
                .flatMap(direct -> Stream.of(false, true)
                        .flatMap(death -> Stream.of("direct", "completion", "execution", "reflection", "proxy")
                                .map(wrapper -> Arguments.of(direct, death, wrapper))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("cleanupFatals")
    void ordinaryBodyFailureDoesNotHideCleanupFatal(boolean direct, boolean death, String wrapper) throws Exception {
        var body = new IllegalStateException("ordinary handler failure");
        Error fatal = death ? new ThreadDeath() : new InternalError("simulated restore fatal");
        try (var fixture = new Fixture(direct, wrap(fatal, wrapper), null, body)) {
            assertSame(fatal, assertThrows(Error.class, fixture::run));
            fixture.assertEnd(InvocationStatus.RETRYING, fatal);
            assertTrue(Arrays.asList(fatal.getSuppressed()).contains(body));
            fixture.assertWorkerFailure(fatal);
            assertEquals(0, fixture.fallbackClears.get());
        }
    }

    static Stream<Arguments> earlierFatals() {
        return Stream.of(false, true)
                .flatMap(direct -> Stream.of(false, true)
                        .flatMap(same ->
                                Stream.of("direct", "completion").map(wrapper -> Arguments.of(direct, same, wrapper))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("earlierFatals")
    void earlierBodyFatalKeepsIdentityWhenRestorationFails(boolean direct, boolean same, String wrapper)
            throws Exception {
        Error first = new InternalError("first handler fatal");
        Error cleanup = same ? first : new ThreadDeath();
        var body = wrap(first, wrapper);
        try (var fixture = new Fixture(direct, cleanup, null, body)) {
            assertSame(first, assertThrows(Error.class, fixture::run));
            fixture.assertEnd(InvocationStatus.RETRYING, first);
            if (!same) assertTrue(Arrays.asList(first.getSuppressed()).contains(cleanup));
            assertTrue(Arrays.stream(first.getSuppressed())
                    .noneMatch(value -> value == first || value instanceof IllegalArgumentException));
            if (same)
                assertFalse(
                        Arrays.asList(first.getSuppressed()).contains(body),
                        "Do not suppress the same fatal's transport wrapper");
            fixture.assertWorkerFailure(first);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void ordinaryRestorationStillClearsAfterABodyFailure(boolean direct, boolean bodyFatal) throws Exception {
        Throwable body =
                bodyFatal ? new InternalError("earlier body fatal") : new IllegalStateException("ordinary body");
        var cleanup = new IllegalStateException("ordinary restore failure");
        try (var fixture = new Fixture(direct, cleanup, null, body)) {
            if (bodyFatal) assertSame(body, assertThrows(Error.class, fixture::run));
            else assertEquals(ExecutionStatus.FAILED, fixture.run().getStatus());
            fixture.assertEnd(bodyFatal ? InvocationStatus.RETRYING : InvocationStatus.FAILED, body);
            assertTrue(Arrays.asList(body.getSuppressed()).contains(cleanup));
            assertEquals(1, fixture.fallbackClears.get());
            fixture.assertWorkerFailure(body);
            assertNull(fixture.markerOnNextTask());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryRestoreFailureClearsMarkerBeforeWorkerReplacement(boolean direct) throws Exception {
        var cleanup = new IllegalStateException("adapter refused saved map");
        try (var fixture = new Fixture(direct, cleanup, null, null)) {
            assertEquals(ExecutionStatus.SUCCEEDED, fixture.run().getStatus());
            fixture.assertEnd(InvocationStatus.SUCCEEDED, null);
            assertEquals(1, fixture.fallbackClears.get());
            fixture.assertWorkerFailure(cleanup);
            assertNull(fixture.markerOnNextTask(), "A replacement worker must not inherit invocation MDC");
            if (!direct) assertEquals(2, fixture.executor.created.get(), "The failed pool worker was replaced");
        }
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({"false,ordinary", "true,ordinary", "false,fatal", "true,fatal", "false,same", "true,same"})
    void fallbackFailureIsGuardedAndRetainsFatalIdentity(boolean direct, String kind) throws Exception {
        var restore = new IllegalStateException("restore refused");
        Throwable clear =
                switch (kind) {
                    case "fatal" -> new ThreadDeath();
                    case "same" -> restore;
                    default -> new IllegalStateException("clear refused");
                };
        try (var fixture = new Fixture(direct, restore, clear, null)) {
            if (clear instanceof Error fatal) {
                assertSame(fatal, assertThrows(Error.class, fixture::run));
                fixture.assertEnd(InvocationStatus.RETRYING, fatal);
                // Manager resource closure may add its own transport diagnostic; retain the exact restoration failure.
                assertTrue(Arrays.asList(fatal.getSuppressed()).contains(restore));
                fixture.assertWorkerFailure(fatal);
            } else {
                assertEquals(ExecutionStatus.SUCCEEDED, fixture.run().getStatus());
                fixture.assertEnd(InvocationStatus.SUCCEEDED, null);
                assertArrayEquals(
                        clear == restore ? new Throwable[0] : new Throwable[] {clear}, restore.getSuppressed());
                fixture.assertWorkerFailure(restore);
            }
            assertEquals(1, fixture.fallbackClears.get(), "No repeated clear attempts against a rejecting adapter");
            // A rejecting clear cannot guarantee a clean replacement. Do not assert quarantine or clean MDC here.
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void normalRestorationPreservesBodyClassificationAndWorker(boolean direct, boolean failedBody) throws Exception {
        var body = failedBody ? new IllegalStateException("ordinary handler failure") : null;
        try (var fixture = new Fixture(direct, null, null, body)) {
            var result = fixture.run();
            assertEquals(failedBody ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED, result.getStatus());
            fixture.assertEnd(failedBody ? InvocationStatus.FAILED : InvocationStatus.SUCCEEDED, body);
            assertNull(fixture.executor.escaped.get());
            assertEquals(0, fixture.fallbackClears.get());
            assertEquals("ambient", fixture.valueOnNextTask("ambient"));
            assertNull(fixture.markerOnNextTask());
            if (!direct) assertEquals(1, fixture.executor.created.get(), "Healthy owner remains reusable");
        }
    }

    private static Throwable wrap(Error fatal, String wrapper) {
        return switch (wrapper) {
            case "completion" -> new CompletionException(new ExecutionException(fatal));
            case "execution" -> new ExecutionException(fatal);
            case "reflection" -> new InvocationTargetException(fatal);
            case "proxy" -> new UndeclaredThrowableException(fatal);
            default -> fatal;
        };
    }

    private static final class Fixture implements AutoCloseable {
        final MDCAdapter original = MDC.getMDCAdapter();
        // SLF4J's standard adapter inherits MDC when ThreadPoolExecutor creates a replacement worker.
        final BasicMDCAdapter delegate = new BasicMDCAdapter();
        final AtomicBoolean injected = new AtomicBoolean();
        final AtomicInteger fallbackClears = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger ends = new AtomicInteger();
        final AtomicInteger factories = new AtomicInteger();
        final AtomicReference<InvocationEndInfo> end = new AtomicReference<>();
        final OwnedExecutor executor;
        final LocalDurableTestRunner<String, String> runner;

        Fixture(boolean direct, Throwable restoreFailure, Throwable clearFailure, Throwable body) throws Exception {
            delegate.put("ambient", "ambient");
            executor = new OwnedExecutor(direct);
            var adapter = (MDCAdapter) Proxy.newProxyInstance(
                    MDCAdapter.class.getClassLoader(), new Class<?>[] {MDCAdapter.class}, (proxy, method, args) -> {
                        if (Thread.currentThread() == executor.owner.get()) {
                            if (restoreFailure != null
                                    && isOuterRestore(method.getName())
                                    && injected.compareAndSet(false, true)) {
                                // Model an adapter that partially mutates its map then rejects restoration.
                                delegate.put(MARKER, "from-invocation");
                                throw restoreFailure;
                            }
                            if (injected.get()
                                    && method.getName().equals("clear")
                                    && Arrays.stream(Thread.currentThread().getStackTrace())
                                            .noneMatch(frame -> frame.getClassName()
                                                            .endsWith(".DurableExecutor")
                                                    && frame.getMethodName().equals("fireOnInvocationEnd"))) {
                                fallbackClears.incrementAndGet();
                                if (clearFailure != null) throw clearFailure;
                            }
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            replaceAdapter(adapter);
            runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        MDC.put(MARKER, "from-handler");
                        if (body != null) ExceptionHelper.sneakyThrow(body);
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(executor)
                            .withPlugins(info -> {
                                factories.incrementAndGet();
                                return new DurableExecutionPlugin() {
                                    public void onInvocationStart(InvocationInfo info) {
                                        starts.incrementAndGet();
                                    }

                                    public void onInvocationEnd(InvocationEndInfo info) {
                                        ends.incrementAndGet();
                                        end.set(info);
                                    }
                                };
                            })
                            .build());
        }

        TestResult<String> run() {
            return runner.run("input");
        }

        void assertEnd(InvocationStatus status, Throwable failure) {
            assertEquals(1, factories.get());
            assertEquals(1, starts.get());
            assertEquals(1, ends.get());
            assertEquals(status, end.get().invocationStatus());
            assertSame(failure, end.get().executionError());
        }

        void assertWorkerFailure(Throwable expected) throws Exception {
            if (executor.direct) {
                assertNull(executor.escaped.get(), "Inline cleanup must preserve normal finalization dispatch");
            } else {
                assertTrue(
                        executor.uncaught.await(2, TimeUnit.SECONDS), "Cleanup failure must escape actual pool worker");
                assertSame(expected, executor.escaped.get());
                assertSame(executor.owner.get(), executor.failedWorker.get());
            }
        }

        String markerOnNextTask() throws Exception {
            return valueOnNextTask(MARKER);
        }

        String valueOnNextTask(String key) throws Exception {
            if (executor.direct) return MDC.get(key);
            return executor.delegate.submit(() -> MDC.get(key)).get(2, TimeUnit.SECONDS);
        }

        public void close() throws Exception {
            try {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                replaceAdapter(original);
            }
        }
    }

    // Ensure the first public handler task finishes before execute returns, so its cleanup is before End dispatch.
    private static final class OwnedExecutor extends AbstractExecutorService {
        final boolean direct;
        final AtomicInteger created = new AtomicInteger();
        final AtomicReference<Thread> owner = new AtomicReference<>();
        final AtomicReference<Thread> failedWorker = new AtomicReference<>();
        final AtomicReference<Throwable> escaped = new AtomicReference<>();
        final CountDownLatch uncaught = new CountDownLatch(1);
        final ThreadPoolExecutor delegate =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                    var thread = new Thread(task, "mdc-cleanup-owner-" + created.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((worker, failure) -> {
                        failedWorker.set(worker);
                        escaped.set(failure);
                        uncaught.countDown();
                    });
                    return thread;
                });

        OwnedExecutor(boolean direct) {
            this.direct = direct;
        }

        public void execute(Runnable task) {
            var done = new CountDownLatch(1);
            Runnable observed = () -> {
                owner.set(Thread.currentThread());
                try {
                    task.run();
                } finally {
                    done.countDown();
                }
            };
            if (direct) observed.run();
            else {
                delegate.execute(observed);
                try {
                    assertTrue(done.await(3, TimeUnit.SECONDS), "Handler cleanup did not finish");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
        }

        public void shutdown() {
            delegate.shutdown();
        }

        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    private static boolean isOuterRestore(String method) {
        if (!method.equals("clear") && !method.equals("setContextMap")) return false;
        var frames = Arrays.asList(Thread.currentThread().getStackTrace());
        return frames.stream()
                        .anyMatch(frame -> frame.getClassName().endsWith(".DurableExecutor")
                                && frame.getMethodName().startsWith("lambda$restoreMdcOnClose"))
                && frames.stream()
                        .noneMatch(frame -> frame.getClassName().endsWith(".DurableExecutor")
                                && frame.getMethodName().equals("fireOnInvocationEnd"));
    }

    private static void replaceAdapter(MDCAdapter adapter) throws Exception {
        var setter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
        setter.setAccessible(true);
        setter.invoke(null, adapter);
    }
}
