// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/** Integration tests verifying plugin hooks fire correctly during durable execution lifecycle. */
class PluginIntegrationTest {

    // ─── Invocation-level hooks ──────────────────────────────────────────

    @Test
    void plugin_receivesInvocationStartAndEnd_onSuccessfulExecution() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step("greet", String.class, stepCtx -> "Hello " + input),
                config);

        var result = runner.runUntilComplete("World");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Verify invocation hooks fired
        assertEquals(1, plugin.invocationStarts.size());
        assertTrue(plugin.invocationStarts.get(0).isFirstInvocation());
        assertNotNull(plugin.invocationStarts.get(0).durableExecutionArn());

        assertEquals(1, plugin.invocationEnds.size());
        assertEquals(InvocationStatus.SUCCEEDED, plugin.invocationEnds.get(0).invocationStatus());
        assertNull(plugin.invocationEnds.get(0).executionError());
    }

    @Test
    void plugin_receivesInvocationEnd_withPendingStatus_onSuspension() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("step1", String.class, stepCtx -> "done");
                    context.wait("pause", Duration.ofMinutes(5));
                    return "complete";
                },
                config);

        var result = runner.run("input");

        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        assertEquals(1, plugin.invocationEnds.size());
        assertEquals(InvocationStatus.PENDING, plugin.invocationEnds.get(0).invocationStatus());
    }

    @Test
    void plugin_receivesInvocationEnd_withFailedStatus_onError() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "failing",
                        String.class,
                        stepCtx -> {
                            throw new RuntimeException("boom");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                config);

        var result = runner.run("input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        assertEquals(1, plugin.invocationEnds.size());
        assertEquals(InvocationStatus.FAILED, plugin.invocationEnds.get(0).invocationStatus());
        assertNotNull(plugin.invocationEnds.get(0).executionError());
    }

    // ─── Operation-level hooks ───────────────────────────────────────────

    @Test
    void plugin_receivesOperationStartAndEnd_forStep() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class, (input, context) -> context.step("my-step", String.class, stepCtx -> "result"), config);

        runner.runUntilComplete("input");

        // Should have at least one operation start for the step
        assertTrue(
                plugin.operationStarts.stream().anyMatch(info -> "my-step".equals(info.name())),
                "Should have onOperationStart for 'my-step'");

        // Should have operation end for completed step
        assertTrue(
                plugin.operationEnds.stream().anyMatch(info -> "my-step".equals(info.name())),
                "Should have onOperationEnd for 'my-step'");
    }

    @Test
    void plugin_receivesOperationStart_forMultipleSteps() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("step-a", String.class, stepCtx -> "A");
                    context.step("step-b", String.class, stepCtx -> "B");
                    context.step("step-c", String.class, stepCtx -> "C");
                    return "done";
                },
                config);

        runner.runUntilComplete("input");

        var opNames = plugin.operationStarts.stream().map(OperationInfo::name).toList();
        assertTrue(opNames.contains("step-a"));
        assertTrue(opNames.contains("step-b"));
        assertTrue(opNames.contains("step-c"));
    }

    @Test
    void plugin_operationEnd_notFiredOnReplay() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("step1", String.class, stepCtx -> "done");
                    context.wait("pause", Duration.ofMinutes(1));
                    context.step("step2", String.class, stepCtx -> "final");
                    return "complete";
                },
                config);

        // First invocation: step1 completes, then suspends at wait
        var result1 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());

        int operationEndsAfterFirstRun = plugin.operationEnds.size();
        assertTrue(operationEndsAfterFirstRun > 0, "At least step1 should have ended");

        // Advance time and re-run (replay step1, execute step2)
        runner.advanceTime();
        var result2 = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());

        // operationEnd for step1 should NOT fire again on replay
        long step1EndCount = plugin.operationEnds.stream()
                .filter(info -> "step1".equals(info.name()))
                .count();
        assertEquals(1, step1EndCount, "step1 onOperationEnd should fire only once (not on replay)");
    }

    @Test
    void plugin_operationEnd_firedForOperationCompletedDuringSuspension() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("step1", String.class, stepCtx -> "done");
                    context.wait("pause", Duration.ofMinutes(1));
                    context.step("step2", String.class, stepCtx -> "final");
                    return "complete";
                },
                config);

        // First invocation: step1 completes, then suspends at wait
        var result1 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());

        // Advance time (wait completes externally while Lambda was frozen)
        runner.advanceTime();

        // Clear plugin state to only track second invocation
        plugin.operationEnds.clear();
        plugin.operationStarts.clear();

        var result2 = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());

        // onOperationEnd for "pause" should fire during the second invocation
        // because the wait completed during suspension (it's in updatedOperationIds)
        long pauseEndCount = plugin.operationEnds.stream()
                .filter(info -> "pause".equals(info.name()))
                .count();
        assertEquals(1, pauseEndCount, "wait operation onOperationEnd should fire when it completes during suspension");
    }

    @Test
    void plugin_operationEnd_firedOnceForStepCompletingInCurrentInvocation() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("step1", String.class, stepCtx -> "done");
                    return "complete";
                },
                config);

        // Single invocation: step1 completes within this invocation
        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        long step1EndCount = plugin.operationEnds.stream()
                .filter(info -> "step1".equals(info.name()))
                .count();
        assertEquals(1, step1EndCount, "step1 onOperationEnd should fire exactly once");
    }

    @Test
    void plugin_operationEnd_includesError_whenInvokeFailsDuringSuspension() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    try {
                        context.invoke("call-target", "target-fn", "{}", String.class);
                    } catch (Exception e) {
                        // expected
                    }
                    return "done";
                },
                config);

        // First invocation: invoke starts, suspends waiting for target
        var result1 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());

        // Target fails while Lambda is frozen
        runner.failChainedInvoke(
                "call-target",
                ErrorObject.builder()
                        .errorType("TargetError")
                        .errorMessage("target function failed")
                        .build());

        // Clear plugin state to only track second invocation
        plugin.operationEnds.clear();

        var result2 = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());

        // onOperationEnd for "call-target" should fire with error info
        var invokeEnd = plugin.operationEnds.stream()
                .filter(info -> "call-target".equals(info.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(invokeEnd, "onOperationEnd should fire for failed invoke during suspension");
        assertNotNull(invokeEnd.error(), "error should be propagated for failed invoke");
        assertEquals("target function failed", invokeEnd.error().getMessage());
    }

    @Test
    void plugin_operationEnd_includesError_whenStepFailsViaCheckpoint() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "failing-step",
                        String.class,
                        stepCtx -> {
                            throw new RuntimeException("step exploded");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                config);

        var result = runner.run("input");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // onOperationEnd for "failing-step" should fire with error info
        var stepEnd = plugin.operationEnds.stream()
                .filter(info -> "failing-step".equals(info.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(stepEnd, "onOperationEnd should fire for failed step");
        assertNotNull(stepEnd.error(), "error should be propagated for failed step");
        assertTrue(stepEnd.error().getMessage().contains("step exploded"));
    }

    @Test
    void plugin_operationEnd_noError_whenOperationSucceeds() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class, (input, context) -> context.step("ok-step", String.class, stepCtx -> "success"), config);

        runner.runUntilComplete("input");

        var stepEnd = plugin.operationEnds.stream()
                .filter(info -> "ok-step".equals(info.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(stepEnd, "onOperationEnd should fire for successful step");
        assertNull(stepEnd.error(), "error should be null for successful step");
    }

    @Test
    void plugin_operationStartAndEnd_balanced_forEmptyMap() {
        var plugin = new RecordingPlugin();
        // withCheckpointEmptyMap is a temporary flag expected to be removed in a future major version.
        var config = DurableConfig.builder()
                .withPlugins(plugin)
                .withCheckpointEmptyMap(true)
                .build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.map("empty-map", List.<String>of(), String.class, (item, index, ctx) -> item);
                    return "done";
                },
                config);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // An empty map must fire a balanced onOperationStart/onOperationEnd pair so plugins
        // (e.g. OTel) do not leave the completed map reported as PENDING.
        long startCount = plugin.operationStarts.stream()
                .filter(info -> "empty-map".equals(info.name()))
                .count();
        long endCount = plugin.operationEnds.stream()
                .filter(info -> "empty-map".equals(info.name()))
                .count();
        assertEquals(1, startCount, "empty map should fire onOperationStart exactly once");
        assertEquals(1, endCount, "empty map should fire onOperationEnd exactly once");
    }

    // ─── Operation change hook ───────────────────────────────────────────

    @Test
    void plugin_receivesOperationChange_forStep() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class, (input, context) -> context.step("my-step", String.class, stepCtx -> "result"), config);

        runner.runUntilComplete("input");

        // A checkpoint response that reports the step at a new status should fire onOperationChange
        assertFalse(
                plugin.operationChanges.isEmpty(), "onOperationChange should fire when an operation changes status");

        var changeWithStep = plugin.operationChanges.stream()
                .filter(info -> info.updatedOperations().values().stream().anyMatch(op -> "my-step".equals(op.name())))
                .findFirst()
                .orElse(null);
        assertNotNull(changeWithStep, "onOperationChange should include the 'my-step' operation that changed status");
        assertNotNull(changeWithStep.durableExecutionArn());
        assertFalse(changeWithStep.operations().isEmpty(), "snapshot of all operations should be present");
    }

    @Test
    void plugin_operationChange_includesErrorAndStatus_whenStepFails() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "failing-step",
                        String.class,
                        stepCtx -> {
                            throw new RuntimeException("step exploded");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                config);

        var result = runner.run("input");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // The failing step transitions through several statuses; find the one reporting FAILED
        var failedItem = plugin.operationChanges.stream()
                .flatMap(info -> info.updatedOperations().values().stream())
                .filter(op -> "failing-step".equals(op.name()) && op.status() == OperationStatus.FAILED)
                .findFirst()
                .orElse(null);
        assertNotNull(failedItem, "onOperationChange should report 'failing-step' with FAILED status");
        assertNotNull(failedItem.error(), "error should be propagated for the failed step");
        assertTrue(failedItem.error().getMessage().contains("step exploded"));
    }

    // ─── User function hooks ─────────────────────────────────────────────

    @Test
    void plugin_receivesUserFunctionStartAndEnd_forStep() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class, (input, context) -> context.step("compute", String.class, stepCtx -> "42"), config);

        runner.runUntilComplete("input");

        assertTrue(
                plugin.userFunctionStarts.stream().anyMatch(info -> "compute".equals(info.name())),
                "Should have onUserFunctionStart for 'compute'");
        assertTrue(
                plugin.userFunctionEnds.stream().anyMatch(info -> "compute".equals(info.name()) && info.succeeded()),
                "Should have successful onUserFunctionEnd for 'compute'");
    }

    @Test
    void plugin_userFunctionEnd_succeeded_false_whenStepFails() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        // When a step fails and retries are exhausted, the step operation's handleStepFailure
        // sends a FAIL checkpoint. However, from runUserHandler's perspective the wrapped lambda
        // catches the exception internally, so onUserFunctionEnd still reports succeeded=true
        // for the step handler wrapper. The actual failure is captured in onOperationEnd and onInvocationEnd.
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "fail-step",
                        String.class,
                        stepCtx -> {
                            throw new RuntimeException("step failed");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                config);

        var result = runner.run("input");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // Verify failure is captured at invocation level
        assertEquals(1, plugin.invocationEnds.size());
        assertEquals(InvocationStatus.FAILED, plugin.invocationEnds.get(0).invocationStatus());

        // The user function start/end hooks fire for the step execution thread
        assertFalse(plugin.userFunctionStarts.isEmpty(), "Should have user function start for the step");
        assertFalse(plugin.userFunctionEnds.isEmpty(), "Should have user function end for the step");
    }

    @Test
    void plugin_userFunctionStart_includesAttemptNumber_forRetries() {
        var attemptCounter = new AtomicInteger(0);
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "retry-step",
                        String.class,
                        stepCtx -> {
                            if (attemptCounter.incrementAndGet() < 3) {
                                throw new RuntimeException("not yet");
                            }
                            return "success";
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                                .build()),
                config);

        // Run until all retries succeed
        runner.runUntilComplete("input");

        // Check that attempt numbers are set on user function starts for the retry step
        var retryStepStarts = plugin.userFunctionStarts.stream()
                .filter(info -> "retry-step".equals(info.name()) && info.attempt() != null)
                .toList();
        assertFalse(retryStepStarts.isEmpty(), "Should have user function starts with attempt numbers");
        assertEquals(Integer.valueOf(1), retryStepStarts.get(0).attempt());
    }

    // ─── Multiple plugins ────────────────────────────────────────────────

    @Test
    void multiplePlugins_allReceiveHooks() {
        var plugin1 = new RecordingPlugin();
        var plugin2 = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin1, plugin2).build();

        var runner = LocalDurableTestRunner.create(
                String.class, (input, context) -> context.step("step", String.class, stepCtx -> "result"), config);

        runner.runUntilComplete("input");

        // Both plugins should receive invocation hooks
        assertEquals(1, plugin1.invocationStarts.size());
        assertEquals(1, plugin2.invocationStarts.size());
        assertEquals(1, plugin1.invocationEnds.size());
        assertEquals(1, plugin2.invocationEnds.size());
    }

    @Test
    void throwingPlugin_doesNotDisruptExecution() {
        var throwingPlugin = new ThrowingPlugin();
        var recordingPlugin = new RecordingPlugin();
        var config = DurableConfig.builder()
                .withPlugins(throwingPlugin, recordingPlugin)
                .build();

        var runner = LocalDurableTestRunner.create(
                String.class, (input, context) -> context.step("step", String.class, stepCtx -> "safe"), config);

        var result = runner.runUntilComplete("input");

        // Execution should succeed despite throwing plugin
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("safe", result.getResult(String.class));

        // The second plugin should still receive hooks
        assertFalse(recordingPlugin.invocationStarts.isEmpty());
        assertFalse(recordingPlugin.invocationEnds.isEmpty());
    }

    // ─── Child context hooks ─────────────────────────────────────────────

    @Test
    void plugin_receivesHooks_forChildContextOperations() {
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.runInChildContext("child", String.class, childCtx -> {
                    return childCtx.step("inner-step", String.class, stepCtx -> "from-child");
                }),
                config);

        runner.runUntilComplete("input");

        // Should have operation start for both child context and inner step
        var opNames = plugin.operationStarts.stream().map(OperationInfo::name).toList();
        assertTrue(opNames.contains("child"), "Should track child context");
        assertTrue(opNames.contains("inner-step"), "Should track inner step");
    }

    // ─── WaitForCondition hooks ──────────────────────────────────────────

    @Test
    void plugin_receivesAttemptNumbers_forWaitForCondition() {
        var checkCount = new AtomicInteger(0);
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var result = context.waitForCondition("poll", String.class, (state, stepCtx) -> {
                        int count = checkCount.incrementAndGet();
                        if (count >= 2) {
                            return WaitForConditionResult.stopPolling("ready");
                        }
                        return WaitForConditionResult.continuePolling("waiting-" + count);
                    });
                    return result;
                },
                config);

        runner.runUntilComplete("input");

        // Should have user function starts with attempt numbers for the condition checks
        var conditionStarts = plugin.userFunctionStarts.stream()
                .filter(info -> "poll".equals(info.name()) && info.attempt() != null)
                .toList();
        assertTrue(conditionStarts.size() >= 2, "Should have at least 2 condition check attempts");
    }

    // ─── Test helper classes ─────────────────────────────────────────────

    /** Plugin that records all hook invocations for assertions. */
    private static class RecordingPlugin implements DurableExecutionPlugin {
        final List<InvocationInfo> invocationStarts = Collections.synchronizedList(new ArrayList<>());
        final List<InvocationEndInfo> invocationEnds = Collections.synchronizedList(new ArrayList<>());
        final List<OperationInfo> operationStarts = Collections.synchronizedList(new ArrayList<>());
        final List<OperationEndInfo> operationEnds = Collections.synchronizedList(new ArrayList<>());
        final List<UserFunctionStartInfo> userFunctionStarts = Collections.synchronizedList(new ArrayList<>());
        final List<UserFunctionEndInfo> userFunctionEnds = Collections.synchronizedList(new ArrayList<>());
        final List<OperationChangeInfo> operationChanges = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onInvocationStart(InvocationInfo info) {
            invocationStarts.add(info);
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            invocationEnds.add(info);
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            operationStarts.add(info);
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            operationEnds.add(info);
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            userFunctionStarts.add(info);
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            userFunctionEnds.add(info);
        }

        @Override
        public void onOperationChange(OperationChangeInfo info) {
            operationChanges.add(info);
        }
    }

    /** Plugin that throws on every hook to verify error isolation. */
    private static class ThrowingPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) {
            throw new RuntimeException("plugin error");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            throw new RuntimeException("plugin error");
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            throw new RuntimeException("plugin error");
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            throw new RuntimeException("plugin error");
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            throw new RuntimeException("plugin error");
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            throw new RuntimeException("plugin error");
        }

        @Override
        public void onOperationChange(OperationChangeInfo info) {
            throw new RuntimeException("plugin error");
        }
    }
}
