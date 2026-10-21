// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestContext;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.util.ExceptionHelper;

class OwnedExecutorSetupFailureTest {
    @SuppressWarnings("removal")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void headerFatalRetainsIdentityWhenOwnedCloseReportsTheSameFatal(boolean death) throws Exception {
        Error failure = death ? new ThreadDeath() : new OutOfMemoryError("simulated reused VM fatal");
        var shutdownCalls = new AtomicInteger();
        var bodies = new AtomicInteger();
        var owned = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void shutdown() {
                shutdownCalls.incrementAndGet();
                super.shutdown();
                throw failure;
            }
        };
        var runtime = mock(RuntimeContext.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(10_000);
        when(runtime.getXrayTraceId()).thenThrow(failure);
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> owned)
                .withPlugins(ignored -> new DurableExecutionPlugin() {})
                .build();
        var input = new DurableExecutionInput(
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
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input,
                            runtime,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                bodies.incrementAndGet();
                                return value;
                            },
                            config));
            assertSame(failure, observed, "Implicit close must not self-suppress a fatal raised before dispatch");
            assertEquals(0, bodies.get());
            assertEquals(1, shutdownCalls.get());
            assertTrue(owned.isTerminated());
        } finally {
            owned.shutdownNow();
            assertTrue(owned.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    enum Setup {
        ORDINARY,
        FATAL,
        WRAPPED_FATAL,
        SAME_FATAL,
        NESTED_WRAPPED_FATAL,
        CHANGING_FATAL_CAUSE,
        THROWING_FATAL_CAUSE,
        APPLICATION_WRAPPER,
        WRAPPED_ORDINARY,
        NESTED_WRAPPED_ORDINARY,
        CHANGING_ORDINARY_CAUSE,
        COMPLETED_APPLICATION_WRAPPER
    }

    enum Cleanup {
        NONE,
        ORDINARY,
        FATAL,
        THREAD_DEATH,
        WRAPPED_FATAL
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({
        "ORDINARY,FATAL,false", "ORDINARY,FATAL,true",
        "ORDINARY,THREAD_DEATH,false", "ORDINARY,THREAD_DEATH,true",
        "ORDINARY,WRAPPED_FATAL,false", "ORDINARY,WRAPPED_FATAL,true",
        "WRAPPED_FATAL,FATAL,false", "WRAPPED_FATAL,FATAL,true",
        "FATAL,FATAL,false", "FATAL,FATAL,true",
        "SAME_FATAL,FATAL,false", "SAME_FATAL,FATAL,true",
        "ORDINARY,NONE,false", "ORDINARY,NONE,true",
        "ORDINARY,ORDINARY,false", "ORDINARY,ORDINARY,true",
        "APPLICATION_WRAPPER,NONE,false", "APPLICATION_WRAPPER,NONE,true",
        "APPLICATION_WRAPPER,FATAL,false", "APPLICATION_WRAPPER,FATAL,true",
        "WRAPPED_FATAL,NONE,false", "WRAPPED_FATAL,NONE,true",
        "NESTED_WRAPPED_FATAL,NONE,false", "NESTED_WRAPPED_FATAL,NONE,true",
        "NESTED_WRAPPED_FATAL,FATAL,false", "NESTED_WRAPPED_FATAL,FATAL,true",
        "CHANGING_FATAL_CAUSE,NONE,false", "CHANGING_FATAL_CAUSE,NONE,true",
        "THROWING_FATAL_CAUSE,NONE,false", "THROWING_FATAL_CAUSE,NONE,true",
        "WRAPPED_ORDINARY,NONE,false", "WRAPPED_ORDINARY,NONE,true",
        "WRAPPED_ORDINARY,ORDINARY,false", "WRAPPED_ORDINARY,ORDINARY,true",
        "WRAPPED_ORDINARY,FATAL,false", "WRAPPED_ORDINARY,FATAL,true",
        "NESTED_WRAPPED_ORDINARY,NONE,false", "NESTED_WRAPPED_ORDINARY,NONE,true",
        "NESTED_WRAPPED_ORDINARY,FATAL,false", "NESTED_WRAPPED_ORDINARY,FATAL,true",
        "CHANGING_ORDINARY_CAUSE,NONE,false", "CHANGING_ORDINARY_CAUSE,NONE,true",
        "COMPLETED_APPLICATION_WRAPPER,NONE,false", "COMPLETED_APPLICATION_WRAPPER,NONE,true",
        "COMPLETED_APPLICATION_WRAPPER,FATAL,false", "COMPLETED_APPLICATION_WRAPPER,FATAL,true"
    })
    void postManagerSetupFailureIsExplicitlyFinalized(Setup setup, Cleanup cleanup, boolean plugins) throws Exception {
        var setupFatal = new InternalError("first setup fatal");
        var setupOrdinary = new IllegalStateException("nonfatal setup cause");
        var applicationCause = new CompletionException(new IllegalStateException("application cause"));
        var innerApplicationDiagnostic = new IllegalArgumentException("retained cause diagnostic");
        applicationCause.addSuppressed(innerApplicationDiagnostic);
        var applicationWrapper = new ExecutionException("application setup failure", applicationCause);
        var applicationDiagnostic = new IllegalStateException("retained application diagnostic");
        applicationWrapper.addSuppressed(applicationDiagnostic);
        var wrapperDiagnostic = new IllegalArgumentException("setup wrapper diagnostic");
        var nestedDiagnostic = new IllegalStateException("nested setup wrapper diagnostic");
        var aliasDiagnostic = new IllegalArgumentException("alias wrapper diagnostic");
        var causeReads = new AtomicInteger();
        Throwable setupFailure = switch (setup) {
            case ORDINARY -> new IllegalStateException("second request ID read failed");
            case FATAL, SAME_FATAL -> setupFatal;
            case WRAPPED_FATAL -> new CompletionException(setupFatal);
            case NESTED_WRAPPED_FATAL -> {
                var inner = new ExecutionException(setupFatal);
                inner.addSuppressed(nestedDiagnostic);
                var alias = new CompletionException(setupFatal);
                alias.addSuppressed(aliasDiagnostic);
                inner.addSuppressed(alias);
                yield new CompletionException(inner);
            }
            case CHANGING_FATAL_CAUSE ->
                new CompletionException("changing cause", null) {
                    @Override
                    public synchronized Throwable getCause() {
                        return causeReads.incrementAndGet() == 1
                                ? setupFatal
                                : new IllegalStateException("second read");
                    }
                };
            case THROWING_FATAL_CAUSE ->
                new CompletionException("fatal cause accessor", null) {
                    @Override
                    public synchronized Throwable getCause() {
                        causeReads.incrementAndGet();
                        throw setupFatal;
                    }
                };
            case APPLICATION_WRAPPER -> applicationWrapper;
            case COMPLETED_APPLICATION_WRAPPER -> new CompletionException(applicationWrapper);
            case WRAPPED_ORDINARY -> new CompletionException(setupOrdinary);
            case NESTED_WRAPPED_ORDINARY -> {
                var inner = new CompletionException(setupOrdinary);
                inner.addSuppressed(nestedDiagnostic);
                var alias = new CompletionException(setupOrdinary);
                alias.addSuppressed(aliasDiagnostic);
                inner.addSuppressed(alias);
                yield new CompletionException(inner);
            }
            case CHANGING_ORDINARY_CAUSE ->
                new CompletionException("changing nonfatal cause", null) {
                    @Override
                    public synchronized Throwable getCause() {
                        return causeReads.incrementAndGet() == 1
                                ? setupOrdinary
                                : new IllegalStateException("second read");
                    }
                };
        };
        var wrappedFatal = setup == Setup.WRAPPED_FATAL
                || setup == Setup.NESTED_WRAPPED_FATAL
                || setup == Setup.CHANGING_FATAL_CAUSE
                || setup == Setup.THROWING_FATAL_CAUSE;
        var wrappedOrdinary = setup == Setup.WRAPPED_ORDINARY
                || setup == Setup.NESTED_WRAPPED_ORDINARY
                || setup == Setup.CHANGING_ORDINARY_CAUSE
                || setup == Setup.COMPLETED_APPLICATION_WRAPPER;
        var setupIsFatal = setup == Setup.FATAL || setup == Setup.SAME_FATAL || wrappedFatal;
        var selectedSetup = setupIsFatal
                ? setupFatal
                : setup == Setup.COMPLETED_APPLICATION_WRAPPER
                        ? applicationWrapper
                        : wrappedOrdinary ? setupOrdinary : setupFailure;
        if (wrappedFatal || wrappedOrdinary) {
            setupFailure.addSuppressed(wrapperDiagnostic);
            // Raw and observed-wrapper aliases must not create a cycle when their diagnostics transfer.
            setupFailure.addSuppressed(selectedSetup);
            if (setup == Setup.NESTED_WRAPPED_FATAL || setup == Setup.NESTED_WRAPPED_ORDINARY) {
                setupFailure.getCause().addSuppressed(setupFailure);
                setupFailure.addSuppressed(nestedDiagnostic);
            }
        }
        Error cleanupFatal = setup == Setup.SAME_FATAL
                ? setupFatal
                : cleanup == Cleanup.THREAD_DEATH ? new ThreadDeath() : new InternalError("owned cleanup fatal");
        Throwable cleanupFailure = switch (cleanup) {
            case NONE -> null;
            case ORDINARY -> new IllegalArgumentException("ordinary owned cleanup failure");
            case FATAL, THREAD_DEATH -> cleanupFatal;
            case WRAPPED_FATAL -> new CompletionException(cleanupFatal);
        };
        var requests = new AtomicInteger();
        var starts = new AtomicInteger();
        var ends = new AtomicInteger();
        var bodies = new AtomicInteger();
        var factoryCalls = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var owned = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
                super.shutdown();
                if (cleanupFailure != null) ExceptionHelper.sneakyThrow(cleanupFailure);
            }
        };
        assertEquals(1, owned.prestartAllCoreThreads());
        var runtime = new TestContext("setup-request") {
            @Override
            public String getAwsRequestId() {
                if (requests.incrementAndGet() == 2) ExceptionHelper.sneakyThrow(setupFailure);
                return super.getAwsRequestId();
            }
        };
        var builder = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> {
                    factoryCalls.incrementAndGet();
                    return owned;
                });
        if (plugins)
            builder.withPlugins(ignored -> new DurableExecutionPlugin() {
                public void onInvocationStart(InvocationInfo info) {
                    starts.incrementAndGet();
                }

                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.incrementAndGet();
                }
            });
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            setupInput(),
                            runtime,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                bodies.incrementAndGet();
                                return value;
                            },
                            builder.build()));
            if (setupIsFatal) {
                assertSame(setupFatal, observed, "The first setup JVM fatal must remain primary");
            } else if (cleanup == Cleanup.FATAL
                    || cleanup == Cleanup.THREAD_DEATH
                    || cleanup == Cleanup.WRAPPED_FATAL) {
                assertSame(
                        cleanupFatal, observed, "A cleanup JVM fatal must outrank an earlier nonfatal setup failure");
            } else if (cleanup == Cleanup.NONE) {
                assertSame(selectedSetup, observed);
            }
            assertTrue(contains(observed, selectedSetup), "Retain setup diagnostics");
            if (cleanupFailure != null)
                assertTrue(
                        contains(observed, cleanup == Cleanup.ORDINARY ? cleanupFailure : cleanupFatal),
                        "Retain later cleanup diagnostics");
            if (setup == Setup.APPLICATION_WRAPPER || setup == Setup.COMPLETED_APPLICATION_WRAPPER) {
                assertSame(applicationCause, applicationWrapper.getCause());
                assertArrayEquals(new Throwable[] {innerApplicationDiagnostic}, applicationCause.getSuppressed());
                assertTrue(contains(observed, applicationDiagnostic));
                assertTrue(contains(observed, innerApplicationDiagnostic));
                assertEquals(setup == Setup.APPLICATION_WRAPPER ? 1 : 2, applicationWrapper.getSuppressed().length);
                assertSame(applicationDiagnostic, applicationWrapper.getSuppressed()[0]);
                assertFalse(
                        Arrays.asList(applicationWrapper.getSuppressed()).contains(innerApplicationDiagnostic),
                        "Do not promote diagnostics from the still-reachable application cause");
            }
            if (wrappedFatal || wrappedOrdinary) {
                assertTrue(contains(observed, wrapperDiagnostic), "The final winner must retain setup diagnostics");
                assertEquals(
                        1,
                        Arrays.stream(selectedSetup.getSuppressed())
                                .filter(e -> e == wrapperDiagnostic)
                                .count());
                assertFalse(
                        contains(observed, setupFailure),
                        "Do not attach a wrapper that points back to the selected cause");
                if (setup == Setup.NESTED_WRAPPED_FATAL || setup == Setup.NESTED_WRAPPED_ORDINARY) {
                    assertEquals(
                            1,
                            Arrays.stream(selectedSetup.getSuppressed())
                                    .filter(e -> e == nestedDiagnostic)
                                    .count());
                    assertEquals(
                            1,
                            Arrays.stream(selectedSetup.getSuppressed())
                                    .filter(e -> e == aliasDiagnostic)
                                    .count());
                }
                if (setup == Setup.CHANGING_FATAL_CAUSE
                        || setup == Setup.THROWING_FATAL_CAUSE
                        || setup == Setup.CHANGING_ORDINARY_CAUSE)
                    assertEquals(1, causeReads.get(), "Inspect each recognized setup wrapper cause only once");
            }
            assertEquals(2, requests.get(), "The failure must occur after the manager claimed its executor");
            assertEquals(1, factoryCalls.get());
            assertEquals(1, shutdowns.get(), "Automatic close must not repeat explicit finalization");
            assertEquals(0, bodies.get());
            assertEquals(0, starts.get());
            assertEquals(0, ends.get());
            assertTrue(owned.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            owned.shutdownNow();
            assertTrue(owned.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonfatalPostManagerSetupStillLeavesSharedExecutorCallerOwned(boolean plugins) throws Exception {
        var failure = new IllegalStateException("second request ID read failed");
        var requests = new AtomicInteger();
        var bodies = new AtomicInteger();
        var hooks = new AtomicInteger();
        var shared = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        shared.prestartAllCoreThreads();
        var runtime = new TestContext("setup-request") {
            @Override
            public String getAwsRequestId() {
                if (requests.incrementAndGet() == 2) throw failure;
                return super.getAwsRequestId();
            }
        };
        var builder = DurableConfig.builder()
                .withExecutorService(shared)
                .withDurableExecutionClient(TestUtils.createMockClient());
        if (plugins)
            builder.withPlugins(ignored -> new DurableExecutionPlugin() {
                public void onInvocationStart(InvocationInfo info) {
                    hooks.incrementAndGet();
                }

                public void onInvocationEnd(InvocationEndInfo info) {
                    hooks.incrementAndGet();
                }
            });
        try {
            assertSame(
                    failure,
                    assertThrows(
                            Throwable.class,
                            () -> DurableExecutor.execute(
                                    setupInput(),
                                    runtime,
                                    TypeToken.get(String.class),
                                    (value, context) -> {
                                        bodies.incrementAndGet();
                                        return value;
                                    },
                                    builder.build())));
            assertEquals(2, requests.get());
            assertEquals(0, bodies.get());
            assertEquals(0, hooks.get());
            assertFalse(shared.isShutdown());
        } finally {
            shared.shutdownNow();
            assertTrue(shared.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static DurableExecutionInput setupInput() {
        return new DurableExecutionInput(
                "arn:execution/setup",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("setup")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
    }

    private static boolean contains(Throwable root, Throwable expected) {
        var queue = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        queue.add(root);
        while (!queue.isEmpty()) {
            var next = queue.remove();
            if (next == expected) return true;
            if (!seen.add(next)) continue;
            var cause = next.getCause();
            if (cause != null) queue.add(cause);
            queue.addAll(Arrays.asList(next.getSuppressed()));
        }
        return false;
    }

    private abstract static class RuntimeContext implements Context {
        @Override
        public String getXrayTraceId() {
            return null;
        }
    }
}
