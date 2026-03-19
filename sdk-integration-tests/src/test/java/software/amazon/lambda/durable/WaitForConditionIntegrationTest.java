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
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.WaitForConditionWaitStrategy;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class WaitForConditionIntegrationTest {

    // ---- Basic integration tests ----

    @Test
    void testBasicPollingSucceedsAfterNChecks() {
        var checkCount = new AtomicInteger(0);
        var targetCount = 3;

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<Integer>exponentialBackoff(
                    60, Duration.ofSeconds(1), Duration.ofSeconds(300), 1.5, JitterStrategy.NONE);

            var config = WaitForConditionConfig.<Integer>builder()
                    .waitStrategy(strategy)
                    .build();

            return ctx.waitForCondition(
                    "poll-counter",
                    Integer.class,
                    (state, stepCtx) -> {
                        checkCount.incrementAndGet();
                        var next = state + 1;
                        return next >= targetCount
                                ? WaitForConditionResult.stopPolling(next)
                                : WaitForConditionResult.continuePolling(next);
                    },
                    0,
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
            // Custom strategy: fixed 2s delay
            WaitForConditionWaitStrategy<String> customStrategy = (state, attempt) -> Duration.ofSeconds(2);

            var config = WaitForConditionConfig.<String>builder()
                    .waitStrategy(customStrategy)
                    .build();

            return ctx.waitForCondition(
                    "custom-strategy",
                    String.class,
                    (state, stepCtx) -> {
                        if ("pending".equals(state)) {
                            return WaitForConditionResult.continuePolling("processing");
                        }
                        return WaitForConditionResult.stopPolling("done");
                    },
                    "pending",
                    config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("done", result.getResult(String.class));
    }

    @Test
    void testMaxAttemptsExceeded() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<String>exponentialBackoff(
                    3, Duration.ofSeconds(1), Duration.ofSeconds(300), 1.5, JitterStrategy.NONE);

            var config = WaitForConditionConfig.<String>builder()
                    .waitStrategy(strategy)
                    .build();

            return ctx.waitForCondition(
                    "max-attempts",
                    String.class,
                    (state, stepCtx) -> WaitForConditionResult.continuePolling(state),
                    "initial",
                    config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void testCheckFunctionError() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<String>exponentialBackoff(
                    60, Duration.ofSeconds(1), Duration.ofSeconds(300), 1.5, JitterStrategy.NONE);

            var config = WaitForConditionConfig.<String>builder()
                    .waitStrategy(strategy)
                    .build();

            return ctx.waitForCondition(
                    "error-check",
                    String.class,
                    (state, stepCtx) -> {
                        throw new IllegalStateException("Check function failed");
                    },
                    "initial",
                    config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        var error = result.getError();
        assertTrue(error.isPresent(), "Error should be present");
        assertEquals("java.lang.IllegalStateException", error.get().errorType());
        assertEquals("Check function failed", error.get().errorMessage());
    }

    @Test
    void testReplayAcrossInvocations() {
        var checkCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<Integer>exponentialBackoff(
                    60, Duration.ofSeconds(1), Duration.ofSeconds(300), 1.5, JitterStrategy.NONE);

            var config = WaitForConditionConfig.<Integer>builder()
                    .waitStrategy(strategy)
                    .build();

            var result = ctx.waitForCondition(
                    "replay-poll",
                    Integer.class,
                    (state, stepCtx) -> {
                        checkCount.incrementAndGet();
                        var next = state + 1;
                        return next >= 2
                                ? WaitForConditionResult.stopPolling(next)
                                : WaitForConditionResult.continuePolling(next);
                    },
                    0,
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

    // ---- isDone=true completes with that state as result ----

    @RepeatedTest(50)
    void propertyStopPollingCompletesWithState() {
        var random = ThreadLocalRandom.current();
        // Generate a random target value between 1 and 10
        var target = random.nextInt(1, 11);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var strategy = WaitStrategies.<Integer>fixedDelay(target + 1, Duration.ofSeconds(1));

            var config = WaitForConditionConfig.<Integer>builder()
                    .waitStrategy(strategy)
                    .build();

            return ctx.waitForCondition(
                    "stop-polling-prop",
                    Integer.class,
                    (state, stepCtx) -> {
                        var next = state + 1;
                        return next >= target
                                ? WaitForConditionResult.stopPolling(next)
                                : WaitForConditionResult.continuePolling(next);
                    },
                    0,
                    config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        // The result should be the state that caused isDone=true — which is target
        assertEquals(target, result.getResult(Integer.class));
    }

    // ---- wait strategy receives correct state and attempt ----

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
                return Duration.ofSeconds(1);
            };

            var config = WaitForConditionConfig.<Integer>builder()
                    .waitStrategy(strategy)
                    .build();

            return ctx.waitForCondition(
                    "state-attempt-prop",
                    Integer.class,
                    (state, stepCtx) -> {
                        var next = state + 1;
                        return next >= totalChecks
                                ? WaitForConditionResult.stopPolling(next)
                                : WaitForConditionResult.continuePolling(next);
                    },
                    0,
                    config);
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // The strategy is only called when isDone=false, so it's called totalChecks-1 times
        // (the last check returns isDone=true, so the strategy is not called)
        var expectedStrategyCalls = totalChecks - 1;
        assertEquals(expectedStrategyCalls, observedStates.size());
        assertEquals(expectedStrategyCalls, observedAttempts.size());

        for (int i = 0; i < expectedStrategyCalls; i++) {
            // Check function returns state + 1, starting from 0
            // So after check i, state = i + 1, and strategy receives that value
            assertEquals(i + 1, observedStates.get(i), "State at strategy call " + (i + 1));
            assertEquals(i, observedAttempts.get(i), "Attempt at strategy call " + (i + 1));
        }
    }
}
