// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    void emptyPluginList_behavesAsNoOp() {
        var runner = new PluginRunner(List.of());

        assertTrue(runner.isEmpty());
        assertDoesNotThrow(() -> runner.onUserFunctionStart(attemptInfo()));
    }

    @Test
    void nullPluginList_behavesAsNoOp() {
        var runner = new PluginRunner(null);

        assertTrue(runner.isEmpty());
        assertDoesNotThrow(() -> runner.onOperationStart(operationInfo()));
    }

    // ─── Fire-and-forget event hooks ─────────────────────────────────────

    @Test
    void fireAndForget_callsAllPlugins() {
        var calls = new ArrayList<String>();
        var plugin1 = new TestPlugin("p1", calls);
        var plugin2 = new TestPlugin("p2", calls);
        var runner = new PluginRunner(List.of(plugin1, plugin2));

        runner.onInvocationStart(invocationInfo());

        assertEquals(List.of("p1:onInvocationStart", "p2:onInvocationStart"), calls);
    }

    @Test
    void fireAndForget_swallowsExceptions() {
        var calls = new ArrayList<String>();
        var throwingPlugin = new ThrowingPlugin();
        var normalPlugin = new TestPlugin("p2", calls);
        var runner = new PluginRunner(List.of(throwingPlugin, normalPlugin));

        assertDoesNotThrow(() -> runner.onInvocationStart(invocationInfo()));
        assertEquals(List.of("p2:onInvocationStart"), calls);
    }

    @Test
    void fireAndForget_callsAllHookTypes() {
        var calls = new ArrayList<String>();
        var plugin = new TestPlugin("p", calls);
        var runner = new PluginRunner(List.of(plugin));

        runner.onInvocationStart(invocationInfo());
        runner.onInvocationEnd(invocationEndInfo());
        runner.onOperationStart(operationInfo());
        runner.onOperationEnd(operationEndInfo());
        runner.onUserFunctionStart(attemptInfo());
        runner.onUserFunctionEnd(attemptEndInfo());

        assertEquals(
                List.of(
                        "p:onInvocationStart",
                        "p:onInvocationEnd",
                        "p:onOperationStart",
                        "p:onOperationEnd",
                        "p:onUserFunctionStart",
                        "p:onUserFunctionEnd"),
                calls);
    }

    // ─── Awaited hooks ───────────────────────────────────────────────────

    @Test
    void awaitedHooks_callAllPlugins() {
        var calls = new ArrayList<String>();
        var plugin1 = new TestPlugin("p1", calls);
        var plugin2 = new TestPlugin("p2", calls);
        var runner = new PluginRunner(List.of(plugin1, plugin2));

        runner.onInvocationEnd(invocationEndInfo());

        assertEquals(List.of("p1:onInvocationEnd", "p2:onInvocationEnd"), calls);
    }

    @Test
    void awaitedHooks_swallowExceptions_butCallRemainingPlugins() {
        var calls = new ArrayList<String>();
        var throwingPlugin = new ThrowingPlugin();
        var normalPlugin = new TestPlugin("p2", calls);
        var runner = new PluginRunner(List.of(throwingPlugin, normalPlugin));

        assertDoesNotThrow(() -> runner.onInvocationEnd(invocationEndInfo()));
        assertEquals(List.of("p2:onInvocationEnd"), calls);
    }

    // ─── Thread safety (basic) ───────────────────────────────────────────

    @Test
    void pluginRunner_isImmutable() {
        var calls = new ArrayList<String>();
        var mutableList = new ArrayList<DurableExecutionPlugin>();
        mutableList.add(new TestPlugin("p1", calls));
        var runner = new PluginRunner(mutableList);

        // Modifying the original list should not affect the runner
        mutableList.add(new TestPlugin("p2", calls));

        runner.onInvocationStart(invocationInfo());

        // Only p1 should be called — p2 was added after construction
        assertEquals(List.of("p1:onInvocationStart"), calls);
    }

    // ─── Helper methods ──────────────────────────────────────────────────

    private static InvocationInfo invocationInfo() {
        return new InvocationInfo("req-123", "arn:aws:lambda:us-east-1:123456789012:function:test", false);
    }

    private static InvocationEndInfo invocationEndInfo() {
        return new InvocationEndInfo(
                "req-123", "arn:aws:lambda:us-east-1:123456789012:function:test", false, null, null);
    }

    private static OperationInfo operationInfo() {
        return new OperationInfo("op-1", "1", "test-step", "STEP", null, null, null, Instant.now(), null);
    }

    private static OperationEndInfo operationEndInfo() {
        return new OperationEndInfo(
                "op-1", "1", "test-step", "STEP", null, null, null, Instant.now(), Instant.now(), null);
    }

    private static UserFunctionStartInfo attemptInfo() {
        return new UserFunctionStartInfo("op-1", "1", "test-step", "STEP", null, null, null, Instant.now(), false, 1);
    }

    private static UserFunctionEndInfo attemptEndInfo() {
        return new UserFunctionEndInfo(
                "op-1", "1", "test-step", "STEP", null, null, null, Instant.now(), Instant.now(), false, 1, true, null);
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
}
