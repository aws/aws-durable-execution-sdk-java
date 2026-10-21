// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.HandlerScoped;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class HandlerOutcomeObservationIntegrationTest {
    static Stream<Arguments> outcomes() {
        return Stream.of("inline", "submit-wait")
                .flatMap(mode -> Stream.of(false, true)
                        .flatMap(retry -> Stream.of(false, true)
                                .flatMap(returnFinally -> Stream.of("none", "plain", "scoped")
                                        .map(plugin -> Arguments.of(mode, retry, returnFinally, plugin)))));
    }

    static Stream<Arguments> successes() {
        return Stream.of("inline", "submit-wait")
                .flatMap(mode -> Stream.of("none", "plain", "scoped").map(plugin -> Arguments.of(mode, plugin)));
    }

    @ParameterizedTest
    @MethodSource("outcomes")
    @Timeout(15)
    void naturalManagerOutcomePrecedesFastHandlerFinally(
            String mode, boolean retry, boolean returnFinally, String pluginKind) throws Exception {
        var workers = new FirstDispatchExecutor(mode);
        var hooks = new CopyOnWriteArrayList<RecordingPlugin>();
        var effects = new AtomicInteger();
        var attempts = new AtomicInteger();
        var overrideFinally = new AtomicBoolean(true);
        var original = new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorMessage("retry invocation").build(), true);
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("saved", String.class, step -> {
                        effects.incrementAndGet();
                        return "saved";
                    });
                    try {
                        if (retry) {
                            context.step("retry-target", String.class, step -> {
                                if (attempts.incrementAndGet() == 1) throw original;
                                return "target";
                            });
                        } else context.wait("pause", Duration.ofSeconds(1));
                        return "done";
                    } finally {
                        if (overrideFinally.get()) {
                            if (returnFinally) return "cleanup result";
                            throw new IllegalStateException("fast handler finally");
                        }
                    }
                },
                config(workers, pluginKind, hooks));
        try {
            if (retry) {
                assertSame(
                        original,
                        assertThrows(UnrecoverableDurableExecutionException.class, () -> runner.run("input")));
            } else assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            assertHooks(hooks, pluginKind, retry ? InvocationStatus.RETRYING : InvocationStatus.PENDING, original);
            overrideFinally.set(false);
            var resumed = runner.runUntilComplete("input");
            assertEquals(ExecutionStatus.SUCCEEDED, resumed.getStatus());
            assertEquals("done", resumed.getResult(String.class));
            assertEquals(1, effects.get(), "Completed step must replay without another side effect");
            if (retry) assertEquals(2, attempts.get());
            if (!pluginKind.equals("none")) {
                assertTrue(hooks.size() >= 2, "Factories create instances for initial invocation and replay");
                for (var plugin : hooks) {
                    assertEquals(1, plugin.starts);
                    assertEquals(1, plugin.ends.size());
                }
                assertEquals(
                        InvocationStatus.SUCCEEDED,
                        hooks.get(hooks.size() - 1).ends.get(0).invocationStatus());
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @MethodSource("successes")
    @Timeout(15)
    void fastOrdinarySuccessKeepsItsResult(String mode, String pluginKind) throws Exception {
        var workers = new FirstDispatchExecutor(mode);
        var hooks = new CopyOnWriteArrayList<RecordingPlugin>();
        var runner = LocalDurableTestRunner.create(
                String.class, (input, context) -> "body result", config(workers, pluginKind, hooks));
        try {
            var result = runner.run("input");
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals("body result", result.getResult(String.class));
            assertHooks(hooks, pluginKind, InvocationStatus.SUCCEEDED, null);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static DurableConfig config(ExecutorService workers, String pluginKind, List<RecordingPlugin> hooks) {
        var builder = DurableConfig.builder().withExecutorService(workers);
        if (!pluginKind.equals("none"))
            builder.withPlugins(info -> {
                var plugin = pluginKind.equals("scoped") ? new ScopedPlugin() : new RecordingPlugin();
                hooks.add(plugin);
                return plugin;
            });
        return builder.build();
    }

    private static void assertHooks(
            List<RecordingPlugin> hooks, String pluginKind, InvocationStatus status, Throwable retry) {
        if (pluginKind.equals("none")) {
            assertTrue(hooks.isEmpty());
            return;
        }
        assertEquals(1, hooks.size());
        var plugin = hooks.get(0);
        assertEquals(1, plugin.starts);
        assertEquals(1, plugin.ends.size());
        var end = plugin.ends.get(0);
        assertEquals(status, end.invocationStatus());
        assertSame(status == InvocationStatus.RETRYING ? retry : null, end.executionError());
        if (plugin instanceof ScopedPlugin scoped) {
            assertSame(plugin.owner, scoped.closedBy);
            assertEquals(1, scoped.closes);
            assertEquals(1, scoped.closesAtEnd);
        }
    }

    private static class RecordingPlugin implements DurableExecutionPlugin {
        Thread owner;
        int starts;
        final List<InvocationEndInfo> ends = new CopyOnWriteArrayList<>();

        public void onInvocationStart(InvocationInfo info) {
            owner = Thread.currentThread();
            starts++;
        }

        public void onInvocationEnd(InvocationEndInfo info) {
            ends.add(info);
        }
    }

    @HandlerScoped(ScopedPlugin.Opener.class)
    private static final class ScopedPlugin extends RecordingPlugin {
        Thread closedBy;
        int closes;
        int closesAtEnd;

        public void onInvocationEnd(InvocationEndInfo info) {
            closesAtEnd = closes;
            super.onInvocationEnd(info);
        }

        public static class Opener implements Function<ScopedPlugin, AutoCloseable> {
            public AutoCloseable apply(ScopedPlugin plugin) {
                return () -> {
                    plugin.closedBy = Thread.currentThread();
                    plugin.closes++;
                };
            }
        }
    }

    /** Completes the first (root handler) dispatch before execute returns; child operations use ordinary workers. */
    private static final class FirstDispatchExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newCachedThreadPool();
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final String mode;

        FirstDispatchExecutor(String mode) {
            this.mode = mode;
        }

        public void execute(Runnable task) {
            if (!first.getAndSet(false)) {
                delegate.execute(task);
            } else if (mode.equals("inline")) {
                task.run();
            } else {
                try {
                    delegate.submit(task).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError("root dispatch failed", failure);
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
}
