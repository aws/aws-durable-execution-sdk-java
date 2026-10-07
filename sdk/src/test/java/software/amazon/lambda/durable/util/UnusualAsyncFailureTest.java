// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.*;

class UnusualAsyncFailureTest {
    @Test
    void cyclicCauseIsBoundedAndEachAccessorIsReadOnce() {
        var first = new CountingWrapper();
        var second = new CountingWrapper();
        first.next = second;
        second.next = first;
        assertSame(first, ExceptionHelper.unwrapAsyncFailure(first));
        assertEquals(1, first.reads.get());
        assertEquals(1, second.reads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "linkage", "assertion"})
    void unreadableNonfatalCauseDoesNotReplaceThePluginFailure(String type) {
        var failure = poisonous(type);
        assertSame(failure, ExceptionHelper.unwrapAsyncFailure(failure));
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "linkage", "assertion"})
    void hookFailureWithUnreadableCauseStillAllowsHealthyPlugin(String type) {
        var healthy = new AtomicInteger();
        var runner = new PluginRunner(List.of(
                info -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationStart(InvocationInfo ignored) {
                        throw poisonous(type);
                    }
                },
                info -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationStart(InvocationInfo ignored) {
                        healthy.incrementAndGet();
                    }
                }));
        assertDoesNotThrow(() -> runner.onInvocationStart(null));
        assertEquals(1, healthy.get());
    }

    @Test
    void completionOnlyCyclesAreBoundedAndCauseLessBehaviorIsPreserved() {
        var first = new CountingWrapper();
        var second = new CountingWrapper();
        first.next = second;
        second.next = first;
        assertSame(first, ExceptionHelper.unwrapCompletableFuture(first));
        assertEquals(1, first.reads.get());
        assertEquals(1, second.reads.get());
        var empty = new CompletionException((Throwable) null);
        var nested = new CompletionException(empty);
        assertNull(ExceptionHelper.unwrapCompletableFuture(nested));
        assertSame(empty, ExceptionHelper.unwrapInvocationFailure(nested));
    }

    @Test
    void invocationNormalizationKeepsApplicationWrapperAndReadsEachCauseOnce() {
        var business = new IllegalArgumentException("business");
        var checkedReads = new AtomicInteger();
        var checked = new ExecutionException(business) {
            @Override
            public synchronized Throwable getCause() {
                assertEquals(1, checkedReads.incrementAndGet());
                return business;
            }
        };
        var transport = new CountingWrapper();
        transport.next = checked;
        assertSame(checked, ExceptionHelper.unwrapInvocationFailure(transport));
        assertEquals(1, transport.reads.get());
        assertEquals(1, checkedReads.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("removal")
    void fatalAccessorIsReportedAtTheScopeBoundary(boolean threadDeath) {
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("cause accessor");
        var wrapper = new CompletionException((Throwable) null) {
            @Override
            public synchronized Throwable getCause() {
                throw fatal;
            }
        };
        assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(wrapper));
        assertSame(
                fatal, ExceptionHelper.unwrapInvocationFailure(new CompletionException(new ExecutionException(fatal))));
        var reported = new AtomicReference<Error>();
        var runner = new PluginRunner(List.of(info -> new Scoped(wrapper)));
        runner.onInvocationStart(null);
        assertSame(
                fatal,
                assertThrows(Error.class, () -> runner.runHandler(() -> "unreachable", () -> {}, reported::set)));
        assertSame(fatal, reported.get());
    }

    @ParameterizedTest
    @CsvSource({
        "factory,runtime",
        "factory,linkage",
        "factory,assertion",
        "scope,runtime",
        "scope,linkage",
        "scope,assertion"
    })
    void unreadableCauseIsContainedInFactoryAndScope(String boundary, String type) {
        var healthy = new AtomicInteger();
        var wrapper = poisonous(type);
        var runner = new PluginRunner(List.of(
                info -> {
                    if (boundary.equals("factory")) throw wrapper;
                    return new Scoped(wrapper);
                },
                info -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationStart(InvocationInfo ignored) {
                        healthy.incrementAndGet();
                    }
                }));
        assertDoesNotThrow(() -> runner.onInvocationStart(null));
        assertEquals("result", assertDoesNotThrow(() -> runner.runHandler(() -> "result")));
        assertEquals(1, healthy.get());
    }

    @HandlerScoped(Opener.class)
    public static final class Scoped implements DurableExecutionPlugin {
        private final CompletionException failure;

        Scoped(CompletionException failure) {
            this.failure = failure;
        }
    }

    public static final class Opener implements Function<Object, AutoCloseable> {
        @Override
        public AutoCloseable apply(Object plugin) {
            return () -> {
                throw ((Scoped) plugin).failure;
            };
        }
    }

    private static CompletionException poisonous(String type) {
        return new CompletionException((Throwable) null) {
            @Override
            public synchronized Throwable getCause() {
                switch (type) {
                    case "linkage":
                        throw new NoSuchMethodError("cause accessor");
                    case "assertion":
                        throw new AssertionError("cause accessor");
                    default:
                        throw new IllegalStateException("cause accessor");
                }
            }
        };
    }

    private static final class CountingWrapper extends CompletionException {
        private final AtomicInteger reads = new AtomicInteger();
        private Throwable next;

        CountingWrapper() {
            super((Throwable) null);
        }

        @Override
        public synchronized Throwable getCause() {
            if (reads.incrementAndGet() > 8) throw new AssertionError("unbounded cause traversal");
            return next;
        }
    }
}
