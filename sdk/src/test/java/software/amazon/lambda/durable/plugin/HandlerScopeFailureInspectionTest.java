// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.util.ExceptionHelper;

class HandlerScopeFailureInspectionTest {
    static Stream<Arguments> ordinaryFailures() {
        return Stream.of("throwing", "cycle", "two-cycle", "changing", "null", "application-cause")
                .flatMap(kind -> Stream.of(false, true)
                        .flatMap(open -> Stream.of(false, true).map(bodyFails -> Arguments.of(kind, open, bodyFails))));
    }

    @ParameterizedTest
    @MethodSource("ordinaryFailures")
    void malformedScopeFailuresKeepTheBodyOutcomeAndReverseCleanup(String kind, boolean failOpen, boolean bodyFails) {
        var calls = new ArrayList<String>();
        var reads = new AtomicInteger();
        var failure = ordinary(kind, reads);
        var owner = Thread.currentThread();
        var runner = new PluginRunner(List.of(
                healthy("first", calls, owner), broken(failOpen, failure, calls), healthy("last", calls, owner)));
        var original = new IllegalStateException("body failure");
        var reported = new AtomicReference<Error>();
        var bodyCalls = new AtomicInteger();
        var body = (Supplier<String>) () -> {
            bodyCalls.incrementAndGet();
            calls.add("body");
            if (bodyFails) throw original;
            return "done";
        };
        if (bodyFails)
            assertSame(
                    original,
                    assertThrows(IllegalStateException.class, () -> runner.runHandler(body, () -> {}, reported::set)));
        else assertEquals("done", runner.runHandler(body, () -> {}, reported::set));
        assertEquals(1, bodyCalls.get());
        assertNull(reported.get());
        assertEquals(
                failOpen
                        ? List.of("open-first", "open-bad", "open-last", "body", "close-last", "close-first")
                        : List.of(
                                "open-first",
                                "open-bad",
                                "open-last",
                                "body",
                                "close-last",
                                "close-bad",
                                "close-first"),
                calls);
        assertEquals(kind.equals("application-cause") ? 0 : kind.equals("two-cycle") ? 2 : 1, reads.get());
    }

    static Stream<Arguments> fatalFailures() {
        return Stream.of("direct", "completion", "execution", "reflection", "proxy", "accessor")
                .flatMap(kind -> Stream.of(false, true)
                        .flatMap(open ->
                                Stream.of(false, true).map(threadDeath -> Arguments.of(kind, open, threadDeath))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("fatalFailures")
    void fatalInspectionReportsBeforeEarlierCleanupAndKeepsIdentity(
            String kind, boolean failOpen, boolean threadDeath) {
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("scope fatal");
        var reported = new AtomicReference<Error>();
        var closed = new AtomicInteger();
        var bodyCalls = new AtomicInteger();
        var owner = Thread.currentThread();
        var earlier = new Scoped(() -> () -> {
            assertSame(owner, Thread.currentThread());
            assertSame(fatal, reported.get(), "notify the invocation before potentially blocking earlier cleanup");
            closed.incrementAndGet();
        });
        var runner = new PluginRunner(List.of(earlier, broken(failOpen, fatalWrapper(kind, fatal), new ArrayList<>())));
        assertSame(
                fatal,
                assertThrows(
                        Error.class,
                        () -> runner.runHandler(
                                () -> {
                                    bodyCalls.incrementAndGet();
                                    return "done";
                                },
                                () -> {},
                                reported::set)));
        assertSame(fatal, reported.get());
        assertEquals(1, closed.get());
        assertEquals(failOpen ? 0 : 1, bodyCalls.get());
    }

    private static Throwable ordinary(String kind, AtomicInteger reads) {
        if (kind.equals("application-cause"))
            return new IllegalStateException("application") {
                @Override
                public synchronized Throwable getCause() {
                    reads.incrementAndGet();
                    return new InternalError("not a transport wrapper");
                }
            };
        var first = new AtomicReference<Throwable>();
        var second = new ExecutionException("second", null) {
            @Override
            public synchronized Throwable getCause() {
                guardNegativeTraversal(reads);
                return first.get();
            }
        };
        var failure = new CompletionException("scope", null) {
            @Override
            public synchronized Throwable getCause() {
                guardNegativeTraversal(reads);
                return switch (kind) {
                    case "throwing" -> throw new IllegalArgumentException("unreadable diagnostic");
                    case "cycle" -> this;
                    case "two-cycle" -> second;
                    case "changing" ->
                        reads.get() == 1
                                ? new IllegalStateException("first cause")
                                : new InternalError("changed cause");
                    case "null" -> null;
                    default -> throw new AssertionError(kind);
                };
            }
        };
        first.set(failure);
        return failure;
    }

    private static void guardNegativeTraversal(AtomicInteger reads) {
        // A broken classifier must fail the negative control rather than leave a spinning test thread.
        if (reads.incrementAndGet() > 8) throw new AssertionError("test safety bound: repeated cause traversal");
    }

    private static Throwable fatalWrapper(String kind, Error fatal) {
        return switch (kind) {
            case "direct" -> fatal;
            case "completion" -> new CompletionException(fatal);
            case "execution" -> new ExecutionException(fatal);
            case "reflection" -> new InvocationTargetException(fatal);
            case "proxy" -> new UndeclaredThrowableException(fatal);
            case "accessor" ->
                new CompletionException("fatal accessor", null) {
                    @Override
                    public synchronized Throwable getCause() {
                        throw fatal;
                    }
                };
            default -> throw new AssertionError(kind);
        };
    }

    private static Scoped healthy(String name, List<String> calls, Thread owner) {
        return new Scoped(() -> {
            assertSame(owner, Thread.currentThread());
            calls.add("open-" + name);
            return () -> {
                assertSame(owner, Thread.currentThread());
                calls.add("close-" + name);
            };
        });
    }

    private static Scoped broken(boolean failOpen, Throwable failure, List<String> calls) {
        return new Scoped(() -> {
            calls.add("open-bad");
            if (failOpen) ExceptionHelper.sneakyThrow(failure);
            return () -> {
                calls.add("close-bad");
                ExceptionHelper.sneakyThrow(failure);
            };
        });
    }

    @FunctionalInterface
    private interface Open {
        AutoCloseable open();
    }

    @HandlerScoped(Opener.class)
    public static final class Scoped implements DurableExecutionPlugin {
        private final Open open;

        private Scoped(Open open) {
            this.open = open;
        }
    }

    public static final class Opener implements Function<Scoped, AutoCloseable> {
        @Override
        public AutoCloseable apply(Scoped plugin) {
            return plugin.open.open();
        }
    }
}
