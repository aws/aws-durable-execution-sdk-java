// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
        var brokenSetup = new DurableExecutionPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                throw new IllegalStateException("setup");
            }
        };
        var brokenClose = new DurableExecutionPlugin() {
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
    @ValueSource(booleans = {false, true})
    void linkageFailuresPreserveTheBodyOutcomeAndAllEarlierScopes(boolean bodyFails) {
        var active = new ThreadLocal<String>();
        var calls = new ArrayList<String>();
        var healthy = new DurableExecutionPlugin() {
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
        var brokenOpen = new DurableExecutionPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                calls.add("open-broken");
                throw new NoSuchMethodError("optional API missing");
            }
        };
        var brokenClose = new DurableExecutionPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                calls.add("open-last");
                return () -> {
                    calls.add("close-last");
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
            assertNull(active.get(), "a later linkage failure must not prevent earlier context cleanup");
            assertEquals(
                    List.of("open-healthy", "open-broken", "open-last", "body", "close-last", "close-healthy"), calls);
        } finally {
            active.remove();
        }
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fatalScopeFailuresStillPropagate(boolean duringOpen) {
        for (Error fatal : List.of(new InternalError("fatal VM failure"), new ThreadDeath())) {
            var plugin = new DurableExecutionPlugin() {
                @Override
                public AutoCloseable openHandlerScope() {
                    if (duringOpen) throw fatal;
                    return () -> {
                        throw fatal;
                    };
                }
            };
            assertSame(
                    fatal, assertThrows(Error.class, () -> new PluginRunner(List.of(plugin)).runHandler(() -> "ok")));
        }
    }

    private static DurableExecutionPlugin scope(String name, List<String> calls, Thread owner) {
        return new DurableExecutionPlugin() {
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
}
