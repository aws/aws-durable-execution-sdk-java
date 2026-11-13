// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestContext;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;

@Timeout(15)
class MdcFinalizationAdjacentTest {
    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void restorationFatalRetainsTaskAndOwnedDiagnosticsOnce(boolean fallbackClear, boolean retrying) throws Exception {
        var original = MDC.getMDCAdapter();
        var before = MDC.getCopyOfContextMap();
        var first = new InternalError("MDC restoration fatal");
        var cleanup = new InternalError("owned shutdown fatal");
        var ordinaryRestore = new IllegalStateException("ordinary restore failure");
        var retry = new UnrecoverableDurableExecutionException(
                ErrorObject.builder()
                        .errorType("test.retry")
                        .errorMessage("retryable handler outcome")
                        .build(),
                true);
        var owner = new AtomicReference<Thread>();
        var workerError = new AtomicReference<Throwable>();
        var escaped = new CountDownLatch(1);
        var outerInjected = new AtomicBoolean();
        var fallbackInjected = new AtomicBoolean();
        var ends = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var shutdowns = new AtomicInteger();
        var users =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), task -> {
                    var thread = new Thread(task, "adjacent-mdc-owner");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        workerError.set(failure);
                        escaped.countDown();
                    });
                    return thread;
                }) {
                    @Override
                    public void shutdown() {
                        shutdowns.incrementAndGet();
                        super.shutdown();
                        throw cleanup;
                    }
                };
        var callers = Executors.newSingleThreadExecutor();
        var proxy = (MDCAdapter) Proxy.newProxyInstance(
                MDCAdapter.class.getClassLoader(), new Class<?>[] {MDCAdapter.class}, (unused, method, arguments) -> {
                    if (Thread.currentThread() == owner.get()) {
                        if ((method.getName().equals("clear")
                                        || method.getName().equals("setContextMap"))
                                && outerRestore()
                                && outerInjected.compareAndSet(false, true))
                            throw fallbackClear ? ordinaryRestore : first;
                        if (fallbackClear
                                && method.getName().equals("clear")
                                && inMethod("clearMdcAfterOrdinaryFailure")
                                && fallbackInjected.compareAndSet(false, true)) throw first;
                    }
                    try {
                        return method.invoke(original, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> users)
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    public void onInvocationStart(InvocationInfo info) {
                        owner.set(Thread.currentThread());
                    }

                    public void onInvocationEnd(InvocationEndInfo info) {
                        assertSame(owner.get(), Thread.currentThread());
                        end.set(info);
                        ends.incrementAndGet();
                    }
                })
                .build();
        try {
            replaceAdapter(proxy);
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    new TestContext("mdc-adjacent"),
                    TypeToken.get(String.class),
                    (value, context) -> {
                        if (retrying) throw retry;
                        return "result";
                    },
                    config));
            var observed = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS))
                    .getCause();
            assertSame(first, observed);
            assertTrue(escaped.await(5, TimeUnit.SECONDS));
            assertSame(first, workerError.get());
            assertTrue(outerInjected.get());
            assertEquals(fallbackClear, fallbackInjected.get());
            assertEquals(1, ends.get());
            assertEquals(
                    retrying ? InvocationStatus.RETRYING : InvocationStatus.SUCCEEDED,
                    end.get().invocationStatus());
            assertSame(
                    retrying ? retry : null,
                    end.get().executionError(),
                    "Later MDC failure must not rewrite or re-fire End");
            assertEquals(
                    1,
                    Arrays.stream(first.getSuppressed())
                            .filter(error -> error == cleanup)
                            .count(),
                    "Retain later owned fatal once");
            if (retrying)
                assertEquals(
                        1,
                        Arrays.stream(first.getSuppressed())
                                .filter(error -> error == retry)
                                .count(),
                        "The pre-existing retry diagnostic must not be duplicated during finalization");
            if (fallbackClear)
                assertEquals(
                        1,
                        Arrays.stream(first.getSuppressed())
                                .filter(error -> error == ordinaryRestore)
                                .count());
            assertEquals(1, shutdowns.get());
        } finally {
            try {
                users.shutdownNow();
                callers.shutdownNow();
                assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                replaceAdapter(original);
                if (before == null) MDC.clear();
                else MDC.setContextMap(before);
            }
        }
    }

    private static boolean outerRestore() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> frame.getClassName().equals(DurableExecutor.class.getName())
                        && frame.getMethodName().startsWith("lambda$restoreMdcOnClose"));
    }

    private static boolean inMethod(String method) {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> frame.getClassName().equals(DurableExecutor.class.getName())
                        && frame.getMethodName().equals(method));
    }

    private static void replaceAdapter(MDCAdapter adapter) throws Exception {
        var method = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
        method.setAccessible(true);
        method.invoke(null, adapter);
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/test",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("test")
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
