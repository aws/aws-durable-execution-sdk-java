// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MdcWorkerReuseBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"start", "end"})
    void replacementWorkerDoesNotInheritInvocationMdcWhenClearSucceeds(String markerPhase) throws Exception {
        exercise(markerPhase, false, "ok");
    }

    static Stream<Arguments> fallbackCases() {
        return Stream.of(false, true)
                .flatMap(suspend -> Stream.of(
                                "ordinary", "assertion", "same", "vm", "death", "wrapped-vm", "wrapped-death")
                        .map(kind -> Arguments.of(suspend, kind)));
    }

    @ParameterizedTest
    @MethodSource("fallbackCases")
    void failedFallbackRetainsFailurePolicyAndFatalIdentity(boolean suspend, String kind) throws Exception {
        exercise("end", suspend, kind);
    }

    static Stream<Arguments> fatalEndCases() {
        return Stream.of(false, true)
                .flatMap(suspend -> Stream.of(false, true)
                        .flatMap(death -> Stream.of(
                                        "ok",
                                        "ordinary",
                                        "assertion",
                                        "same",
                                        "vm",
                                        "death",
                                        "wrapped-vm",
                                        "wrapped-death")
                                .map(kind -> Arguments.of(suspend, death, kind))));
    }

    @ParameterizedTest
    @MethodSource("fatalEndCases")
    @SuppressWarnings("removal")
    void fatalEndStillClearsOrdinaryRestoreFailureBeforeWorkerReplacement(boolean suspend, boolean death, String kind)
            throws Exception {
        exercise("end", suspend, kind, death ? new ThreadDeath() : new InternalError("original End fatal"));
    }

    private static void exercise(String markerPhase, boolean suspend, String kind) throws Exception {
        exercise(markerPhase, suspend, kind, null);
    }

    @SuppressWarnings("removal")
    private static void exercise(String markerPhase, boolean suspend, String kind, Error selectedFatal)
            throws Exception {
        var original = MDC.getMDCAdapter();
        var basic = new BasicMDCAdapter();
        var ends = new AtomicInteger();
        var endStatus = new AtomicReference<InvocationStatus>();
        var failed = new AtomicBoolean();
        var clearTried = new AtomicBoolean();
        var restoreFailure = new IllegalStateException("restore failed");
        Error clearFatal =
                switch (kind) {
                    case "vm", "wrapped-vm" -> new InternalError("fallback fatal");
                    case "death", "wrapped-death" -> new ThreadDeath();
                    default -> null;
                };
        Throwable clearFailure =
                switch (kind) {
                    case "ordinary" -> new IllegalArgumentException("clear failed");
                    case "assertion" -> new AssertionError("clear failed");
                    case "same" -> restoreFailure;
                    case "wrapped-vm", "wrapped-death" -> new CompletionException(new ExecutionException(clearFatal));
                    default -> clearFatal;
                };
        Error expectedFatal = selectedFatal != null ? selectedFatal : clearFatal;
        var escaped = new CountDownLatch(1);
        var failedWorker = new AtomicReference<Thread>();
        var ownerFailure = new AtomicReference<Throwable>();
        var dirtyAtFailure = new AtomicReference<String>();
        var count = new AtomicInteger();
        var workers = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "mdc-inheritable-worker-" + count.incrementAndGet());
            thread.setUncaughtExceptionHandler((owner, failure) -> {
                failedWorker.set(owner);
                ownerFailure.set(failure);
                dirtyAtFailure.set(MDC.get("invocation-marker"));
                escaped.countDown();
            });
            return thread;
        });
        try {
            var adapter = (MDCAdapter) Proxy.newProxyInstance(
                    MDCAdapter.class.getClassLoader(), new Class<?>[] {MDCAdapter.class}, (proxy, method, args) -> {
                        if (ends.get() == 1
                                && method.getName().equals("setContextMap")
                                && Thread.currentThread().getName().startsWith("mdc-inheritable-worker-")
                                && failed.compareAndSet(false, true)) throw restoreFailure;
                        if (failed.get()
                                && method.getName().equals("clear")
                                && Thread.currentThread().getName().startsWith("mdc-inheritable-worker-")
                                && clearTried.compareAndSet(false, true)
                                && clearFailure != null) throw clearFailure;
                        try {
                            return method.invoke(basic, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            replace(adapter);
            MDC.put("ambient", "saved");
            var plugin = new DurableExecutionPlugin() {
                public void onInvocationStart(InvocationInfo info) {
                    if (markerPhase.equals("start")) MDC.put("invocation-marker", "previous-invocation");
                }

                public void onInvocationEnd(InvocationEndInfo info) {
                    if (markerPhase.equals("end")) MDC.put("invocation-marker", "previous-invocation");
                    endStatus.set(info.invocationStatus());
                    ends.incrementAndGet();
                    if (selectedFatal != null) throw selectedFatal;
                }
            };
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (value, context) -> {
                        if (suspend) context.wait("resume", Duration.ofSeconds(1));
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(plugin)
                            .build());
            if (expectedFatal != null) assertSame(expectedFatal, assertThrows(Error.class, () -> runner.run("input")));
            else
                assertEquals(
                        suspend ? ExecutionStatus.PENDING : ExecutionStatus.SUCCEEDED,
                        runner.run("input").getStatus());
            assertTrue(escaped.await(3, TimeUnit.SECONDS));
            assertSame(expectedFatal != null ? expectedFatal : restoreFailure, ownerFailure.get());

            assertEquals(1, ends.get());
            assertEquals(suspend ? InvocationStatus.PENDING : InvocationStatus.SUCCEEDED, endStatus.get());
            if (selectedFatal != null) {
                assertEquals(
                        clearFailure != null && clearFailure != restoreFailure
                                ? List.of(restoreFailure, clearFailure)
                                : List.of(restoreFailure),
                        List.of(selectedFatal.getSuppressed()));
            } else if (expectedFatal != null)
                assertEquals(List.of(restoreFailure), List.of(expectedFatal.getSuppressed()));
            else
                assertEquals(
                        clearFailure != null && clearFailure != restoreFailure ? List.of(clearFailure) : List.of(),
                        List.of(restoreFailure.getSuppressed()));
            var nextThread = workers.submit(Thread::currentThread).get(3, TimeUnit.SECONDS);
            var inherited = workers.submit(() -> MDC.get("invocation-marker")).get(3, TimeUnit.SECONDS);
            assertNotSame(failedWorker.get(), nextThread);
            System.out.println("MDC_REUSE_PUBLIC phase=" + markerPhase + " kind=" + kind + " replaced=true oldMarker="
                    + dirtyAtFailure.get() + " inheritedMarker=" + inherited);
            if (clearFailure == null) assertNull(inherited, "Successful fallback clears inheritable invocation state");
            assertTrue(clearTried.get());
            // An adapter whose clear also fails cannot promise clean replacement state; its failure is exposed.
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            basic.clear();
            replace(original);
        }
    }

    private static void replace(MDCAdapter adapter) throws Exception {
        var setter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
        setter.setAccessible(true);
        setter.invoke(null, adapter);
    }
}
