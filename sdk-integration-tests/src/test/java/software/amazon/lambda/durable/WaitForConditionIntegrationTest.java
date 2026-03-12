// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class WaitForConditionIntegrationTest {

    // ---- 5.1: Basic integration tests ----

    @Test
    void testBasicPollingSucceedsAfterNChecks() {
        var checkCount = new AtomicInteger(0);
        var targetCount = 3;

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<Integer>builder(state -> state < targetCount)
                    .initialDelay(Duration.ofSeconds(1))
                    .jitter(JitterStrategy.NONE)
                    .build();

            var config = WaitForConditionConfig.<Integer>builder(strategy, 0).build();

            return ctx.waitForCondition(
                    "poll-counter",
                    Integer.class,
                    (state, stepCtx) -> {
                        checkCount.incrementAndGet();
                        return state + 1;
                    },
                    config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(targetCount, result.getResult(Integer.class));
        assertEquals(targetCount, checkCount.get());
    }

    @Test
    void testCustomWaitStrategy() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Custom strategy: stop when state equals "done", fixed 2s delay
            WaitForConditionWaitStrategy<String> customStrategy = (state, attempt) -> {
                if ("done".equals(state)) {
                    return WaitForConditionDecision.stopPolling();
                }
                return WaitForConditionDecision.continuePolling(Duration.ofSeconds(2));
            };

            var config = WaitForConditionConfig.<String>builder(customStrategy, "pending")
                    .build();

            return ctx.waitForCondition(
                    "custom-strategy",
                    String.class,
                    (state, stepCtx) -> {
                        if ("pending".equals(state)) {
                            return "processing";
                        }
                        return "done";
                    },
                    config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("done", result.getResult(String.class));
    }

    @Test
    void testMaxAttemptsExceeded() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<String>builder(state -> true) // always continue
                    .maxAttempts(3)
                    .initialDelay(Duration.ofSeconds(1))
                    .jitter(JitterStrategy.NONE)
                    .build();

            var config =
                    WaitForConditionConfig.<String>builder(strategy, "initial").build();

            return ctx.waitForCondition("max-attempts", String.class, (state, stepCtx) -> state, config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void testCheckFunctionError() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<String>builder(state -> true)
                    .initialDelay(Duration.ofSeconds(1))
                    .jitter(JitterStrategy.NONE)
                    .build();

            var config =
                    WaitForConditionConfig.<String>builder(strategy, "initial").build();

            return ctx.waitForCondition(
                    "error-check",
                    String.class,
                    (state, stepCtx) -> {
                        throw new IllegalStateException("Check function failed");
                    },
                    config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void testReplayAcrossInvocations() {
        var checkCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<Integer>builder(state -> state < 2)
                    .initialDelay(Duration.ofSeconds(1))
                    .jitter(JitterStrategy.NONE)
                    .build();

            var config = WaitForConditionConfig.<Integer>builder(strategy, 0).build();

            var result = ctx.waitForCondition(
                    "replay-poll",
                    Integer.class,
                    (state, stepCtx) -> {
                        checkCount.incrementAndGet();
                        return state + 1;
                    },
                    config);

            return result.toString();
        });

        // First run completes the waitForCondition
        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var firstCheckCount = checkCount.get();

        // Second run should replay — check function should NOT re-execute
        var result2 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("2", result2.getResult(String.class));
        // Check count should not increase on replay
        assertEquals(firstCheckCount, checkCount.get());
    }

    // ---- 5.2: PBT — stopPolling completes with that state as result ----
    // **Validates: Requirements 1.5, 2.1**

    @RepeatedTest(50)
    void propertyStopPollingCompletesWithState() {
        var random = ThreadLocalRandom.current();
        // Generate a random target value between 1 and 10
        var target = random.nextInt(1, 11);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Strategy: stop when state reaches target
            WaitForConditionWaitStrategy<Integer> strategy = (state, attempt) -> {
                if (state >= target) {
                    return WaitForConditionDecision.stopPolling();
                }
                return WaitForConditionDecision.continuePolling(Duration.ofSeconds(1));
            };

            var config = WaitForConditionConfig.<Integer>builder(strategy, 0).build();

            return ctx.waitForCondition("stop-polling-prop", Integer.class, (state, stepCtx) -> state + 1, config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        // The result should be the state that caused stopPolling — which is target
        assertEquals(target, result.getResult(Integer.class));
    }

    // ---- 5.3: PBT — wait strategy receives correct state and attempt ----
    // **Validates: Requirements 1.3, 2.1**

    @RepeatedTest(50)
    void propertyWaitStrategyReceivesCorrectStateAndAttempt() {
        var random = ThreadLocalRandom.current();
        var totalChecks = random.nextInt(1, 8); // 1 to 7 checks before stopping

        var observedStates = new ArrayList<Integer>();
        var observedAttempts = new ArrayList<Integer>();

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            WaitForConditionWaitStrategy<Integer> strategy = (state, attempt) -> {
                observedStates.add(state);
                observedAttempts.add(attempt);
                if (attempt + 1 >= totalChecks) {
                    return WaitForConditionDecision.stopPolling();
                }
                return WaitForConditionDecision.continuePolling(Duration.ofSeconds(1));
            };

            var config = WaitForConditionConfig.<Integer>builder(strategy, 0).build();

            return ctx.waitForCondition("state-attempt-prop", Integer.class, (state, stepCtx) -> state + 1, config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Verify each strategy call received the correct state and attempt
        assertEquals(totalChecks, observedStates.size());
        assertEquals(totalChecks, observedAttempts.size());

        for (int i = 0; i < totalChecks; i++) {
            // Check function returns state + 1, starting from 0
            // So after check i, state = i + 1
            assertEquals(i + 1, observedStates.get(i), "State at strategy call " + (i + 1));
            assertEquals(i, observedAttempts.get(i), "Attempt at strategy call " + (i + 1));
        }
    }
}
