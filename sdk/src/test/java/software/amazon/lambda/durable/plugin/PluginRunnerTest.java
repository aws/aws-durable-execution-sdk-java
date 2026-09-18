// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PluginRunnerTest {

    // ─── No-op / empty behavior ──────────────────────────────────────────

    @Test
    void noOpRunner_doesNothing() {
        var runner = PluginRunner.noOp();

        assertTrue(runner.isEmpty());
        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        assertDoesNotThrow(() -> runner.onInvocationEnd(invocationEndInfo()));
    }

    @Test
    void emptyFactoryList_behavesAsNoOp() {
        var runner = new PluginRunner(List.of());

        assertTrue(runner.isEmpty());
        assertDoesNotThrow(() -> runner.onUserFunctionStart(attemptInfo()));
    }

    @Test
    void nullFactoryList_behavesAsNoOp() {
        var runner = new PluginRunner(null);

        assertTrue(runner.isEmpty());
        assertDoesNotThrow(() -> runner.onOperationStart(operationInfo()));
    }

    // ─── Per-invocation lifetime ─────────────────────────────────────────

    @Test
    void invocationStart_createsOnePluginPerFactory_andPassesTheHookInfo() {
        var calls = new ArrayList<String>();
        var receivedByFactory = new ArrayList<InvocationInfo>();
        var receivedByHook = new ArrayList<InvocationInfo>();
        var runner = new PluginRunner(List.of(info -> {
            receivedByFactory.add(info);
            return new TestPlugin("p1", calls) {
                @Override
                public void onInvocationStart(InvocationInfo hookInfo) {
                    receivedByHook.add(hookInfo);
                    super.onInvocationStart(hookInfo);
                }
            };
        }));
        var info = invocationInfo();

        runner.onInvocationStart(info);

        assertEquals(List.of("p1:onInvocationStart"), calls);
        assertEquals(1, receivedByFactory.size());
        assertSame(info, receivedByFactory.get(0), "the factory must receive this invocation's info");
        assertSame(info, receivedByHook.get(0), "the first hook must receive the same info instance");
    }

    @Test
    void everyFactoryRunsBeforeAnyStartHook_andNoPluginSeesAnothersHookState() {
        // The order is part of the contract, so it is pinned rather than left to the reply on a review thread. Every
        // factory runs, then every start hook, and a plugin therefore cannot observe what another plugin's start hook
        // installed. That is deliberate: a plugin that depended on it would be depending on the order entries appear in
        // a customer's withPlugins call, and instrumentation that changes what other instrumentation records is not
        // something the SDK can promise across three languages.
        //
        // Both run on the same thread, so the ThreadLocal below is visible where it is set; only the interleaving is
        // being asserted, not visibility.
        var order = new ArrayList<String>();
        var seenByLaterConstructor = new ArrayList<String>();
        var installed = new ThreadLocal<String>();

        DurableExecutionPluginFactory first = info -> {
            order.add("construct:first");
            return new DurableExecutionPlugin() {
                @Override
                public void onInvocationStart(InvocationInfo hookInfo) {
                    order.add("start:first");
                    installed.set("from-first-start-hook");
                }
            };
        };
        DurableExecutionPluginFactory second = info -> {
            order.add("construct:second");
            seenByLaterConstructor.add(String.valueOf(installed.get()));
            return new DurableExecutionPlugin() {
                @Override
                public void onInvocationStart(InvocationInfo hookInfo) {
                    order.add("start:second");
                }
            };
        };

        try {
            new PluginRunner(List.of(first, second)).onInvocationStart(invocationInfo());
        } finally {
            installed.remove();
        }

        assertEquals(List.of("construct:first", "construct:second", "start:first", "start:second"), order);
        assertEquals(List.of("null"), seenByLaterConstructor, "a constructor must not observe another plugin's hook");
    }

    @Test
    void factoriesAreCalledOncePerInvocation_notPerHook() {
        var creations = new AtomicInteger();
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> {
            creations.incrementAndGet();
            return new TestPlugin("p", calls);
        }));

        runner.onInvocationStart(invocationInfo());
        runner.onOperationStart(operationInfo());
        runner.onOperationEnd(operationEndInfo());
        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(1, creations.get());
        assertEquals(
                List.of("p:onInvocationStart", "p:onOperationStart", "p:onOperationEnd", "p:onInvocationEnd"), calls);
    }

    @Test
    void eachInvocationGetsItsOwnPluginInstance() {
        var instances = new ArrayList<DurableExecutionPlugin>();
        DurableExecutionPluginFactory factory = info -> {
            var plugin = new TestPlugin("p", new ArrayList<>());
            instances.add(plugin);
            return plugin;
        };

        // One runner per invocation, as the SDK creates one per ExecutionManager.
        new PluginRunner(List.of(factory)).onInvocationStart(invocationInfo());
        new PluginRunner(List.of(factory)).onInvocationStart(invocationInfo());

        assertEquals(2, instances.size());
        assertNotSame(instances.get(0), instances.get(1), "invocations must not share a plugin instance");
    }

    @Test
    void hooksBeforeInvocationStart_dispatchToNothing() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> new TestPlugin("p", calls)));

        // Plugins only exist between onInvocationStart and the end of the invocation.
        runner.onOperationStart(operationInfo());

        assertTrue(calls.isEmpty());
    }

    @Test
    void releasePlugins_dropsThisInvocationsInstances() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> new TestPlugin("p", calls)));
        runner.onInvocationStart(invocationInfo());
        calls.clear();

        runner.releasePlugins();
        runner.onOperationStart(operationInfo());
        runner.onInvocationEnd(invocationEndInfo());

        assertTrue(calls.isEmpty(), "released plugin instances must not receive further hooks");
    }

    @Test
    void factoryList_isCopiedAtConstruction() {
        var calls = new ArrayList<String>();
        var mutableList = new ArrayList<DurableExecutionPluginFactory>();
        mutableList.add(info -> new TestPlugin("p1", calls));
        var runner = new PluginRunner(mutableList);

        // Modifying the original list should not affect the runner
        mutableList.add(info -> new TestPlugin("p2", calls));

        runner.onInvocationStart(invocationInfo());

        // Only p1 should be called — p2 was added after construction
        assertEquals(List.of("p1:onInvocationStart"), calls);
    }

    // ─── Factory error isolation ─────────────────────────────────────────

    @Test
    void throwingFactory_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> {
                    throw new RuntimeException("boom");
                },
                info -> new TestPlugin("p2", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(List.of("p2:onInvocationStart", "p2:onInvocationEnd"), calls);
    }

    @Test
    void nullReturningFactory_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> null, info -> new TestPlugin("p2", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        runner.onOperationStart(operationInfo());

        assertEquals(List.of("p2:onInvocationStart", "p2:onOperationStart"), calls);
    }

    // ─── Factory and hook linkage failures ───────────────────────────────
    //
    // A LinkageError is an Error, not an Exception, so a catch of Exception does not contain it. Both of the shapes
    // below are reachable through the plugin contract rather than hypothetical: a provider JAR compiled against an
    // earlier version of DurableExecutionPluginProvider throws AbstractMethodError the first time the SDK invokes the
    // method it does not implement, and a provider whose optional dependency is absent from the deployment package
    // throws NoClassDefFoundError when it first touches that class. Both must be contained, because the contract says a
    // factory or hook failure is logged and skipped and never disrupts the execution.

    @Test
    void factoryThrowingAbstractMethodError_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> {
                    // What a provider compiled against the previous interface throws when the new factory method is
                    // invoked on it.
                    throw new AbstractMethodError(
                            "software.amazon.example.LegacyProvider.createPlugin(InvocationInfo)");
                },
                info -> new TestPlugin("p2", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(List.of("p2:onInvocationStart", "p2:onInvocationEnd"), calls);
    }

    @Test
    void factoryThrowingNoClassDefFoundError_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> {
                    // What a provider with a missing optional dependency throws while building its plugin.
                    throw new NoClassDefFoundError("software/amazon/example/OptionalExporter");
                },
                info -> new TestPlugin("p2", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(List.of("p2:onInvocationStart", "p2:onInvocationEnd"), calls);
    }

    @Test
    void hookThrowingLinkageError_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> new LinkageErrorPlugin(),
                info -> new TestPlugin("p2", calls),
                info -> new TestPlugin("p3", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        calls.clear();
        assertDoesNotThrow(() -> runner.onOperationStart(operationInfo()));
        assertDoesNotThrow(() -> runner.onInvocationEnd(invocationEndInfo()));

        assertEquals(
                List.of("p2:onOperationStart", "p3:onOperationStart", "p2:onInvocationEnd", "p3:onInvocationEnd"),
                calls);
    }

    // ─── Factory and hook throwables that are neither Exception nor LinkageError ──
    //
    // AssertionError and ServiceConfigurationError extend Error and Error respectively, and neither is a LinkageError,
    // so a catch of `Exception | LinkageError` lets both escape. Escaping the plugin boundary fails the invocation the
    // plugin was only observing. The contract says a factory or hook failure is logged and skipped and never disrupts
    // the execution, so both must be contained. Both shapes are reachable through the plugin contract: a plugin that
    // ships with assertions enabled, or that calls a library which asserts internally, throws AssertionError, and a
    // plugin that runs its own ServiceLoader over its exporter back ends throws ServiceConfigurationError when one of
    // them is misdeclared.

    @Test
    void factoryThrowingAssertionError_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> {
                    throw new AssertionError("plugin invariant violated");
                },
                info -> new TestPlugin("p2", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(List.of("p2:onInvocationStart", "p2:onInvocationEnd"), calls);
    }

    @Test
    void factoryThrowingServiceConfigurationError_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> {
                    throw new ServiceConfigurationError("software.amazon.example.Exporter: provider not found");
                },
                info -> new TestPlugin("p2", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(List.of("p2:onInvocationStart", "p2:onInvocationEnd"), calls);
    }

    @Test
    void hookThrowingAssertionError_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> new AssertionErrorPlugin(),
                info -> new TestPlugin("p2", calls),
                info -> new TestPlugin("p3", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        calls.clear();
        assertDoesNotThrow(() -> runner.onOperationStart(operationInfo()));
        assertDoesNotThrow(() -> runner.onInvocationEnd(invocationEndInfo()));

        assertEquals(
                List.of("p2:onOperationStart", "p3:onOperationStart", "p2:onInvocationEnd", "p3:onInvocationEnd"),
                calls);
    }

    @Test
    void hookThrowingServiceConfigurationError_isContained_andRemainingPluginsStillRun() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> new ServiceConfigurationErrorPlugin(),
                info -> new TestPlugin("p2", calls),
                info -> new TestPlugin("p3", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        calls.clear();
        assertDoesNotThrow(() -> runner.onInvocationEnd(invocationEndInfo()));

        assertEquals(List.of("p2:onInvocationEnd", "p3:onInvocationEnd"), calls);
    }

    // ─── Interrupts ──────────────────────────────────────────────────────
    //
    // An InterruptedException from plugin code is contained like any other non-fatal throwable, and the interrupt
    // status is left alone. onInvocationStart runs on the handler thread, so setting the flag there would leave the
    // handler's next blocking call to fail with an interrupt no user code asked for. A thrown InterruptedException is
    // also no proof of interruption: no hook and no factory method declares a checked exception, so plugin code reaches
    // the boundary with one only by rethrowing it undeclared, and it can construct one with the status clear. The tests
    // below use that shape deliberately.

    @Test
    void factoryThrowingInterruptedException_isContained_andLeavesTheThreadUninterrupted() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(
                info -> {
                    sneakyThrow(new InterruptedException("flush interrupted"));
                    return null;
                },
                info -> new TestPlugin("p2", calls)));

        try {
            assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));

            assertFalse(
                    Thread.currentThread().isInterrupted(),
                    "containment must not interrupt the thread that runs the handler");
            assertEquals(List.of("p2:onInvocationStart"), calls);
        } finally {
            // Clear the status so a failure here does not leak into whatever else runs on this thread.
            Thread.interrupted();
        }
    }

    @Test
    void hookThrowingInterruptedException_isContained_andLeavesTheThreadUninterrupted() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> new InterruptingPlugin(), info -> new TestPlugin("p2", calls)));
        runner.onInvocationStart(invocationInfo());
        calls.clear();

        try {
            assertDoesNotThrow(() -> runner.onInvocationEnd(invocationEndInfo()));

            assertFalse(
                    Thread.currentThread().isInterrupted(),
                    "containment must not interrupt the thread that runs the handler");
            assertEquals(List.of("p2:onInvocationEnd"), calls, "remaining plugins must still be called");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void containmentPreservesAnInterruptTheThreadAlreadyCarried() {
        // The boundary neither sets nor clears the flag: a thread that was already interrupted before it entered plugin
        // code still carries the interrupt when containment returns.
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> new ThrowingPlugin(), info -> new TestPlugin("p2", calls)));
        runner.onInvocationStart(invocationInfo());
        calls.clear();

        try {
            Thread.currentThread().interrupt();

            assertDoesNotThrow(() -> runner.onInvocationEnd(invocationEndInfo()));

            assertTrue(Thread.currentThread().isInterrupted(), "an interrupt the thread already carried must survive");
            assertEquals(List.of("p2:onInvocationEnd"), calls);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void factoryThrowingAJvmError_stillPropagates() {
        // The containment is deliberately narrow: an Error that says the JVM itself is failing must not be swallowed as
        // if it were a plugin defect, because the process cannot be assumed able to continue.
        var runner = new PluginRunner(List.of(info -> {
            throw new OutOfMemoryError("Java heap space");
        }));

        assertThrows(OutOfMemoryError.class, () -> runner.onInvocationStart(invocationInfo()));
    }

    @Test
    void hookThrowingAJvmError_stillPropagates() {
        var runner = new PluginRunner(List.of(info -> new StackOverflowPlugin()));

        assertThrows(StackOverflowError.class, () -> runner.onInvocationStart(invocationInfo()));
    }

    @Test
    void factoryThrowingAnyVirtualMachineError_stillPropagates() {
        // The fatal set is named by the VirtualMachineError supertype rather than by listing its subclasses, so an
        // InternalError propagates for the same reason OutOfMemoryError does. This pins the supertype, not the list.
        var runner = new PluginRunner(List.of(info -> {
            throw new InternalError("JVM internal invariant violated");
        }));

        assertThrows(InternalError.class, () -> runner.onInvocationStart(invocationInfo()));
    }

    @Test
    void hookThrowingAnyVirtualMachineError_stillPropagates() {
        var runner = new PluginRunner(List.of(info -> new UnknownErrorPlugin()));

        assertThrows(UnknownError.class, () -> runner.onInvocationStart(invocationInfo()));
    }

    // ─── Thread termination ──────────────────────────────────────────────
    //
    // Thread.stop() terminates a thread by throwing ThreadDeath into it, which unwinds that thread's stack from
    // wherever it stood and releases the monitors it held over state it had only half updated. maven.compiler.source is
    // 17, and Thread.stop() still delivers ThreadDeath on a JDK 17 runtime, so the delivery is possible on a runtime
    // this SDK supports. Containing the ThreadDeath would return the factory or hook thread to the SDK and user work it
    // carries after the plugin returns, with that thread's invariants already broken and the termination dropped. The
    // runner therefore rethrows it, at both the factory boundary and the hook boundary.
    //
    // These tests throw the ThreadDeath directly. Thread.stop() throws UnsupportedOperationException on the JDK 20 or
    // later runtime the build uses, so a test cannot ask the JVM to deliver one.

    @Test
    @SuppressWarnings("removal") // ThreadDeath is deprecated for removal since JDK 20.
    void factoryThrowingThreadDeath_stillPropagates() {
        var runner = new PluginRunner(List.of(info -> {
            throw new ThreadDeath();
        }));

        assertThrows(ThreadDeath.class, () -> runner.onInvocationStart(invocationInfo()));
    }

    @Test
    @SuppressWarnings("removal") // ThreadDeath is deprecated for removal since JDK 20.
    void hookThrowingThreadDeath_stillPropagates() {
        var runner = new PluginRunner(List.of(info -> new ThreadDeathPlugin()));

        assertThrows(ThreadDeath.class, () -> runner.onInvocationStart(invocationInfo()));
    }

    // ─── Fire-and-forget event hooks ─────────────────────────────────────

    @Test
    void fireAndForget_callsAllPlugins() {
        var calls = new ArrayList<String>();
        var runner =
                new PluginRunner(List.of(info -> new TestPlugin("p1", calls), info -> new TestPlugin("p2", calls)));

        runner.onInvocationStart(invocationInfo());

        assertEquals(List.of("p1:onInvocationStart", "p2:onInvocationStart"), calls);
    }

    @Test
    void fireAndForget_swallowsExceptions() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> new ThrowingPlugin(), info -> new TestPlugin("p2", calls)));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        assertEquals(List.of("p2:onInvocationStart"), calls);
    }

    @Test
    void fireAndForget_callsAllHookTypes() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> new TestPlugin("p", calls)));

        runner.onInvocationStart(invocationInfo());
        runner.onOperationStart(operationInfo());
        runner.onOperationEnd(operationEndInfo());
        runner.onOperationChange(operationChangeInfo());
        runner.onUserFunctionStart(attemptInfo());
        runner.onUserFunctionEnd(attemptEndInfo());
        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(
                List.of(
                        "p:onInvocationStart",
                        "p:onOperationStart",
                        "p:onOperationEnd",
                        "p:onOperationChange",
                        "p:onUserFunctionStart",
                        "p:onUserFunctionEnd",
                        "p:onInvocationEnd"),
                calls);
    }

    // ─── Awaited hooks ───────────────────────────────────────────────────

    @Test
    void awaitedHooks_callAllPlugins() {
        var calls = new ArrayList<String>();
        var runner =
                new PluginRunner(List.of(info -> new TestPlugin("p1", calls), info -> new TestPlugin("p2", calls)));
        runner.onInvocationStart(invocationInfo());
        calls.clear();

        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(List.of("p1:onInvocationEnd", "p2:onInvocationEnd"), calls);
    }

    @Test
    void awaitedHooks_swallowExceptions_butCallRemainingPlugins() {
        var calls = new ArrayList<String>();
        var runner = new PluginRunner(List.of(info -> new ThrowingPlugin(), info -> new TestPlugin("p2", calls)));
        runner.onInvocationStart(invocationInfo());
        calls.clear();

        assertDoesNotThrow(() -> runner.onInvocationEnd(invocationEndInfo()));
        assertEquals(List.of("p2:onInvocationEnd"), calls);
    }

    // ─── Execution input / result components ─────────────────────────────

    @Test
    void invocationInfo_compatibilityConstructor_leavesExecutionInputNull() {
        var info = new InvocationInfo("req-123", "arn:test", false, Instant.now());

        assertNull(info.executionInput());
    }

    @Test
    void invocationInfo_carriesExecutionInput() {
        var input = Map.of("name", "World");

        var info = new InvocationInfo("req-123", "arn:test", true, Instant.now(), input);

        assertEquals(input, info.executionInput());
    }

    @Test
    void invocationInfo_rejectsNullExecutionStartTime() {
        var exception =
                assertThrows(NullPointerException.class, () -> new InvocationInfo("req-123", "arn:test", true, null));

        assertEquals("executionStartTime", exception.getMessage());
    }

    @Test
    void invocationEndInfo_compatibilityConstructor_leavesExecutionInputAndResultNull() {
        var info = new InvocationEndInfo("req-123", "arn:test", false, InvocationStatus.SUCCEEDED, null);

        assertNull(info.executionInput());
        assertNull(info.executionResult());
    }

    @Test
    void invocationEndInfo_carriesExecutionInputAndResult() {
        var info = new InvocationEndInfo(
                "req-123", "arn:test", false, InvocationStatus.SUCCEEDED, null, "World", "Hello World");

        assertEquals("World", info.executionInput());
        assertEquals("Hello World", info.executionResult());
    }

    @Test
    void invocationInfo_toString_omitsExecutionInput() {
        var info = new InvocationInfo("req-123", "arn:test", true, Instant.now(), "s3cret-input");

        var rendered = info.toString();

        assertFalse(rendered.contains("s3cret-input"), "execution input must not leak into logs");
        assertTrue(rendered.contains("req-123"));
        assertTrue(rendered.contains("arn:test"));
    }

    @Test
    void invocationEndInfo_toString_omitsExecutionInputAndResult() {
        var info = new InvocationEndInfo(
                "req-123", "arn:test", false, InvocationStatus.SUCCEEDED, null, "s3cret-input", "s3cret-result");

        var rendered = info.toString();

        assertFalse(rendered.contains("s3cret-input"), "execution input must not leak into logs");
        assertFalse(rendered.contains("s3cret-result"), "execution result must not leak into logs");
        assertTrue(rendered.contains("req-123"));
        assertTrue(rendered.contains("SUCCEEDED"));
    }

    // ─── Helper methods ──────────────────────────────────────────────────

    private static InvocationInfo invocationInfo() {
        return new InvocationInfo(
                "req-123", "arn:aws:lambda:us-east-1:123456789012:function:test", false, Instant.now());
    }

    private static InvocationEndInfo invocationEndInfo() {
        return new InvocationEndInfo(
                "req-123", "arn:aws:lambda:us-east-1:123456789012:function:test", false, null, null);
    }

    private static OperationInfo operationInfo() {
        return new OperationInfo("op-1", "test-step", "STEP", null, null, Instant.now(), null, null, false);
    }

    private static OperationEndInfo operationEndInfo() {
        return new OperationEndInfo(
                "op-1",
                "test-step",
                "STEP",
                null,
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null);
    }

    private static OperationChangeInfo operationChangeInfo() {
        return new OperationChangeInfo(
                "req-123", "arn:aws:lambda:us-east-1:123456789012:function:test", Map.of(), Map.of());
    }

    private static UserFunctionStartInfo attemptInfo() {
        return new UserFunctionStartInfo("op-1", "test-step", "STEP", null, null, Instant.now(), false, 1);
    }

    private static UserFunctionEndInfo attemptEndInfo() {
        return new UserFunctionEndInfo(
                "op-1",
                "test-step",
                "STEP",
                null,
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                UserFunctionOutcome.SUCCEEDED,
                null);
    }

    // ─── Test plugin implementations ─────────────────────────────────────

    /** Plugin that records which hooks were called. */
    private static class TestPlugin implements DurableExecutionPlugin {
        private final String name;
        private final List<String> calls;

        TestPlugin(String name, List<String> calls) {
            this.name = name;
            this.calls = calls;
        }

        @Override
        public void onInvocationStart(InvocationInfo info) {
            calls.add(name + ":onInvocationStart");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            calls.add(name + ":onInvocationEnd");
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            calls.add(name + ":onOperationStart");
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            calls.add(name + ":onOperationEnd");
        }

        @Override
        public void onOperationChange(OperationChangeInfo info) {
            calls.add(name + ":onOperationChange");
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            calls.add(name + ":onUserFunctionStart");
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            calls.add(name + ":onUserFunctionEnd");
        }
    }

    /** Plugin that throws on every hook. */
    private static class ThrowingPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) {
            throw new RuntimeException("boom");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            throw new RuntimeException("boom");
        }
    }

    /**
     * Plugin whose hooks fail to link, as a plugin compiled against a different SDK version or missing an optional
     * dependency does.
     */
    private static class LinkageErrorPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) {
            throw new NoClassDefFoundError("software/amazon/example/OptionalExporter");
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            throw new AbstractMethodError("software.amazon.example.LegacyPlugin.onOperationStart(OperationInfo)");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            throw new IncompatibleClassChangeError("software.amazon.example.LegacyPlugin");
        }
    }

    /** Plugin whose hook reports that the JVM itself is failing, which must not be contained. */
    private static class StackOverflowPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) {
            throw new StackOverflowError();
        }
    }

    /** Plugin whose hook throws a VirtualMachineError other than the two the older tests pin. */
    private static class UnknownErrorPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) {
            throw new UnknownError("unknown JVM failure");
        }
    }

    /** Plugin whose hook thread has been terminated by {@code Thread.stop()}, which must not be contained. */
    @SuppressWarnings("removal") // ThreadDeath is deprecated for removal since JDK 20.
    private static class ThreadDeathPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) {
            throw new ThreadDeath();
        }
    }

    /** Plugin whose hooks fail an assertion, as a plugin running with assertions enabled does. */
    private static class AssertionErrorPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) {
            throw new AssertionError("plugin invariant violated");
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            throw new AssertionError("plugin invariant violated");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            throw new AssertionError("plugin invariant violated");
        }
    }

    /** Plugin whose hook fails its own service lookup, as a plugin loading its exporter back ends does. */
    private static class ServiceConfigurationErrorPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) {
            throw new ServiceConfigurationError("software.amazon.example.Exporter: provider not found");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            throw new ServiceConfigurationError("software.amazon.example.Exporter: provider not found");
        }
    }

    /** Plugin whose awaited hook is interrupted while flushing and rethrows the InterruptedException undeclared. */
    private static class InterruptingPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            sneakyThrow(new InterruptedException("flush interrupted"));
        }
    }

    /**
     * Throws {@code t} without declaring it, which is how plugin code can reach the runner with an
     * {@link InterruptedException} even though no hook signature permits a checked exception.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
