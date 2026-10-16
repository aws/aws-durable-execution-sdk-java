// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.lambda.durable.config.StepSemantics.AT_MOST_ONCE_PER_RETRY;
import static software.amazon.lambda.durable.retry.RetryStrategies.Presets.NO_RETRY;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class CheckpointTokenRevocationIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptedStepOutcomeReplaysAfterResumeWithoutRepeatingUserCode(boolean fail) {
        var calls = new AtomicInteger();
        var runner = createPausedRunner(fail, calls);

        assertEquals(ExecutionStatus.PENDING, runner.runUntilComplete("input").getStatus());
        assertEquals(1, calls.get());
        runner.resumeExecution();
        var resumed = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, resumed.getStatus());
        assertEquals(fail ? "stored-error" : "stored-result", resumed.getResult(String.class));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptedStepOutcomeIsReportedToPluginsAfterResume(boolean fail) {
        var calls = new AtomicInteger();
        var plugin = new RecordingPlugin();
        var config = DurableConfig.builder().withPlugins(plugin).build();
        var runner = createPausedRunner(fail, calls, config);

        assertEquals(ExecutionStatus.PENDING, runner.runUntilComplete("input").getStatus());
        assertTrue(plugin.operationEnds.isEmpty());
        runner.resumeExecution();
        assertEquals(ExecutionStatus.SUCCEEDED, runner.runUntilComplete("input").getStatus());

        assertPluginObservedAcceptedOutcome(plugin, fail);
    }

    private LocalDurableTestRunner<String, String> createPausedRunner(boolean fail, AtomicInteger calls) {
        return createPausedRunner(fail, calls, DurableConfig.builder().build());
    }

    private LocalDurableTestRunner<String, String> createPausedRunner(
            boolean fail, AtomicInteger calls, DurableConfig config) {
        var runnerRef = new AtomicReference<LocalDurableTestRunner<String, String>>();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    try {
                        return ctx.step(
                                "once",
                                String.class,
                                stepCtx -> {
                                    calls.incrementAndGet();
                                    runnerRef.get().pauseExecution();
                                    if (fail) {
                                        throw new IllegalArgumentException("stored-error");
                                    }
                                    return "stored-result";
                                },
                                noRetryConfig());
                    } catch (IllegalArgumentException e) {
                        return e.getMessage();
                    }
                },
                config);
        runnerRef.set(runner);
        return runner;
    }

    private void assertPluginObservedAcceptedOutcome(RecordingPlugin plugin, boolean fail) {
        assertEquals(2, plugin.invocationStarts.size());
        var updatedOperation = plugin.invocationStarts.get(1).updatedOperations().values().stream()
                .filter(operation -> "once".equals(operation.name()))
                .findFirst()
                .orElseThrow();
        var expectedStatus = fail ? OperationStatus.FAILED : OperationStatus.SUCCEEDED;
        assertEquals(expectedStatus, updatedOperation.status());
        assertTrue(updatedOperation.isReplay());
        assertStoredOutcome(updatedOperation.result(), updatedOperation.error(), fail);

        var operationEnds = plugin.operationEnds.stream()
                .filter(operation -> "once".equals(operation.name()))
                .toList();
        assertEquals(1, operationEnds.size());
        var operationEnd = operationEnds.get(0);
        assertEquals(expectedStatus.toString(), operationEnd.status());
        assertTrue(operationEnd.isReplay());
        assertStoredOutcome(operationEnd.result(), operationEnd.error(), fail);
    }

    private void assertStoredOutcome(String result, Throwable error, boolean fail) {
        if (fail) {
            assertNull(result);
            assertNotNull(error);
            assertTrue(error.getMessage().contains("stored-error"));
        } else {
            assertEquals("\"stored-result\"", result);
            assertNull(error);
        }
    }

    private StepConfig noRetryConfig() {
        return StepConfig.builder()
                .semanticsPerRetry(AT_MOST_ONCE_PER_RETRY)
                .retryStrategy(NO_RETRY)
                .build();
    }

    private static class RecordingPlugin implements DurableExecutionPlugin {
        private final List<InvocationInfo> invocationStarts = Collections.synchronizedList(new ArrayList<>());
        private final List<OperationEndInfo> operationEnds = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onInvocationStart(InvocationInfo info) {
            invocationStarts.add(info);
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            operationEnds.add(info);
        }
    }
}
