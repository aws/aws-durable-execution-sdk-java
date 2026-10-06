// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.util.ExceptionHelper;

class HandlerScopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "suspension"})
    void scopesCloseInReverseOrderOnTheHandlerThread(String outcome) {
        var calls = new ArrayList<String>();
        var owner = Thread.currentThread();
        var runner = new PluginRunner(List.of(scope("a", calls, owner), scope("b", calls, owner)));
        Throwable error =
                outcome.equals("suspension") ? new SuspendExecutionException() : new IllegalStateException("user");
        if (outcome.equals("success")) {
            assertEquals("ok", runner.runHandler(() -> {
                calls.add("handler");
                return "ok";
            }));
        } else {
            assertSame(
                    error,
                    assertThrows(
                            Throwable.class,
                            () -> runner.runHandler(() -> {
                                calls.add("handler");
                                ExceptionHelper.sneakyThrow(error);
                                return null;
                            })));
        }
        assertEquals(List.of("open-a", "open-b", "handler", "close-b", "close-a"), calls);
    }

    @Test
    void ordinarySetupAndCleanupFailuresDoNotReplaceTheHandlerResult() {
        var calls = new ArrayList<String>();
        var brokenSetup = new ScopedPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                throw new IllegalStateException("setup");
            }
        };
        var brokenClose = new ScopedPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                return () -> {
                    throw new IllegalStateException("cleanup");
                };
            }
        };
        var runner = new PluginRunner(List.of(scope("first", calls, Thread.currentThread()), brokenSetup, brokenClose));
        assertEquals("ok", runner.runHandler(() -> "ok"));
        assertEquals(List.of("open-first", "close-first"), calls);
    }

    @ParameterizedTest
    @CsvSource({
        "false,linkage",
        "true,linkage",
        "false,assert-open",
        "true,assert-open",
        "false,assert-close",
        "true,assert-close"
    })
    void nonFatalErrorsPreserveTheBodyOutcomeAndAllEarlierScopes(boolean bodyFails, String failureSite) {
        var active = new ThreadLocal<String>();
        var calls = new ArrayList<String>();
        var healthy = new ScopedPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                calls.add("open-healthy");
                active.set("healthy");
                return () -> {
                    calls.add("close-healthy");
                    active.remove();
                };
            }
        };
        var brokenOpen = new ScopedPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                calls.add("open-broken");
                if (failureSite.equals("assert-open")) throw new AssertionError("optional setup assertion");
                throw new NoSuchMethodError("optional API missing");
            }
        };
        var brokenClose = new ScopedPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                calls.add("open-last");
                return () -> {
                    calls.add("close-last");
                    if (failureSite.equals("assert-close")) throw new AssertionError("optional cleanup assertion");
                    throw new NoClassDefFoundError("optional class missing");
                };
            }
        };
        var runner = new PluginRunner(List.of(healthy, brokenOpen, brokenClose));
        var bodyFailure = new IllegalStateException("original body error");
        Supplier<String> body = () -> {
            calls.add("body");
            assertEquals("healthy", active.get());
            if (bodyFails) throw bodyFailure;
            return "ok";
        };
        try {
            if (bodyFails)
                assertSame(bodyFailure, assertThrows(IllegalStateException.class, () -> runner.runHandler(body)));
            else assertEquals("ok", runner.runHandler(body));
            assertNull(active.get(), "a later optional-scope failure must not prevent earlier context cleanup");
            assertEquals(
                    List.of("open-healthy", "open-broken", "open-last", "body", "close-last", "close-healthy"), calls);
        } finally {
            active.remove();
        }
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void fatalScopeFailuresStillPropagate(boolean duringOpen, boolean wrapped) {
        for (Error fatal : List.of(new InternalError("fatal VM failure"), new ThreadDeath())) {
            var plugin = new ScopedPlugin() {
                @Override
                public AutoCloseable openHandlerScope() {
                    if (duringOpen) {
                        if (wrapped)
                            CompletableFuture.failedFuture(new ExecutionException(fatal))
                                    .join();
                        throw fatal;
                    }
                    return () -> {
                        if (wrapped)
                            CompletableFuture.failedFuture(new ExecutionException(fatal))
                                    .join();
                        throw fatal;
                    };
                }
            };
            var reported = new AtomicReference<Error>();
            var calls = new ArrayList<String>();
            var active = new ThreadLocal<String>();
            var owner = Thread.currentThread();
            var healthy = new ScopedPlugin() {
                @Override
                public AutoCloseable openHandlerScope() {
                    active.set("healthy");
                    calls.add("open-healthy");
                    return () -> {
                        assertSame(owner, Thread.currentThread());
                        active.remove();
                        calls.add("close-healthy");
                    };
                }
            };
            var runner = new PluginRunner(List.of(healthy, plugin));
            assertSame(fatal, assertThrows(Error.class, () -> runner.runHandler(() -> "ok", () -> {}, reported::set)));
            assertSame(fatal, reported.get(), "only a scope callback reports this fatal");
            assertNull(active.get(), "earlier context must not leak into reuse of the owner thread");
            assertEquals(List.of("open-healthy", "close-healthy"), calls);
        }
    }

    @Test
    void openerConstructionLinkageFailurePreservesBodyAndEarlierCleanup() {
        var calls = new ArrayList<String>();
        var runner =
                new PluginRunner(List.of(scope("healthy", calls, Thread.currentThread()), new BrokenOpenerPlugin()));
        assertEquals("body", runner.runHandler(() -> "body"));
        assertEquals(List.of("open-healthy", "close-healthy"), calls);
    }

    @Test
    void openerConstructionFatalRetainsIdentityAndEarlierCleanup() {
        var calls = new ArrayList<String>();
        var reported = new AtomicReference<Error>();
        var runner =
                new PluginRunner(List.of(scope("healthy", calls, Thread.currentThread()), new FatalOpenerPlugin()));
        assertSame(
                FatalOpener.FAILURE,
                assertThrows(
                        InternalError.class,
                        () -> runner.runHandler(() -> fail("handler must not run"), () -> {}, reported::set)));
        assertSame(FatalOpener.FAILURE, reported.get());
        assertEquals(List.of("open-healthy", "close-healthy"), calls);
    }

    @HandlerScoped(BrokenOpener.class)
    private static class BrokenOpenerPlugin implements DurableExecutionPlugin {}

    public static class BrokenOpener implements Function<BrokenOpenerPlugin, AutoCloseable> {
        public BrokenOpener() {
            throw new NoSuchMethodError("opener dependency");
        }

        public AutoCloseable apply(BrokenOpenerPlugin plugin) {
            return null;
        }
    }

    @HandlerScoped(FatalOpener.class)
    private static class FatalOpenerPlugin implements DurableExecutionPlugin {}

    public static class FatalOpener implements Function<FatalOpenerPlugin, AutoCloseable> {
        static final InternalError FAILURE = new InternalError("opener fatal");

        public FatalOpener() {
            throw FAILURE;
        }

        public AutoCloseable apply(FatalOpenerPlugin plugin) {
            return null;
        }
    }

    private static DurableExecutionPlugin scope(String name, List<String> calls, Thread owner) {
        return new ScopedPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                assertSame(owner, Thread.currentThread());
                calls.add("open-" + name);
                return () -> {
                    assertSame(owner, Thread.currentThread());
                    calls.add("close-" + name);
                };
            }
        };
    }

    @HandlerScoped(ScopedPlugin.Opener.class)
    private abstract static class ScopedPlugin implements DurableExecutionPlugin {
        public abstract AutoCloseable openHandlerScope();

        public static class Opener implements Function<ScopedPlugin, AutoCloseable> {
            public AutoCloseable apply(ScopedPlugin plugin) {
                return plugin.openHandlerScope();
            }
        }
    }
}
