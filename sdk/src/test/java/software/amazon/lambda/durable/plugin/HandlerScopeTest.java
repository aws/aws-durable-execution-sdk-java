// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
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
