// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.retry.JitterStrategy;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;

class StepSemanticsEdgeCasesTest {

    @Test
    void testAtLeastOnceInterruptedStepReExecutes() {
        var executionCount = new AtomicInteger(0);
        var runner = new LocalDurableTestRunner<>(String.class, (input, ctx) -> 
            ctx.step("my-step", String.class, () -> {
                executionCount.incrementAndGet();
                return "result";
            }, StepConfig.builder().semantics(StepSemantics.AT_LEAST_ONCE_PER_RETRY).build()));

        // Simulate interrupted step with proper attempt tracking
        runner.getStorage().simulateInterruptedStep("1", "my-step", 0);

        var result = runner.runUntilComplete("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get()); // Should re-execute once
    }

    @Test
    void testAtMostOnceInterruptedStepWithNoRetryFails() {
        var executionCount = new AtomicInteger(0);
        var runner = new LocalDurableTestRunner<>(String.class, (input, ctx) -> 
            ctx.step("my-step", String.class, () -> {
                executionCount.incrementAndGet();
                return "result";
            }, StepConfig.builder()
                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build()));

        // Simulate interrupted step
        runner.getStorage().simulateInterruptedStep("1", "my-step", 0);

        var result = runner.runUntilComplete("test-input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(0, executionCount.get()); // Should not execute user code
        
        // Verify StepInterruptedException was the cause
        var updates = result.getStorage().getOperationUpdates();
        var failUpdate = updates.stream()
            .filter(u -> u.action() == OperationAction.FAIL)
            .findFirst();
        assertTrue(failUpdate.isPresent());
        assertEquals("StepInterruptedException", failUpdate.get().error().errorType());
    }

    @Test
    void testAtMostOnceInterruptedStepWithRetrySucceeds() {
        var executionCount = new AtomicInteger(0);
        var runner = new LocalDurableTestRunner<>(String.class, (input, ctx) -> 
            ctx.step("my-step", String.class, () -> {
                executionCount.incrementAndGet();
                return "result";
            }, StepConfig.builder()
                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                .build()));

        // Simulate interrupted step
        runner.getStorage().simulateInterruptedStep("1", "my-step", 0);

        var result = runner.runUntilComplete("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get()); // Should execute once after retry
        
        // Verify retry was attempted
        var updates = result.getStorage().getOperationUpdates();
        assertTrue(updates.stream().anyMatch(u -> u.action() == OperationAction.RETRY));
    }

    @Test
    void testPendingStepExecutesAfterDelay() {
        var executionCount = new AtomicInteger(0);
        var runner = new LocalDurableTestRunner<>(String.class, (input, ctx) -> 
            ctx.step("my-step", String.class, () -> {
                executionCount.incrementAndGet();
                return "result";
            }));

        // Simulate step in PENDING status (waiting for retry)
        runner.getStorage().simulatePendingStep("1", "my-step", 1, java.time.Instant.now().plusSeconds(1));

        var result = runner.runUntilComplete("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testStepFailureWithRetryExhaustion() {
        var executionCount = new AtomicInteger(0);
        var runner = new LocalDurableTestRunner<>(String.class, (input, ctx) -> 
            ctx.step("my-step", String.class, () -> {
                executionCount.incrementAndGet();
                throw new RuntimeException("Always fails");
            }, StepConfig.builder()
                .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                .build()));

        var result = runner.runUntilComplete("test-input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(3, executionCount.get()); // Initial + 2 retries
    }

    @Test
    void testMultipleStepsWithDifferentSemantics() {
        var atLeastOnceCount = new AtomicInteger(0);
        var atMostOnceCount = new AtomicInteger(0);
        
        var runner = new LocalDurableTestRunner<>(String.class, (input, ctx) -> {
            var result1 = ctx.step("step1", String.class, () -> {
                atLeastOnceCount.incrementAndGet();
                return "result1";
            }, StepConfig.builder().semantics(StepSemantics.AT_LEAST_ONCE_PER_RETRY).build());
            
            var result2 = ctx.step("step2", String.class, () -> {
                atMostOnceCount.incrementAndGet();
                return "result2";
            }, StepConfig.builder().semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY).build());
            
            return result1 + "," + result2;
        });

        var result = runner.runUntilComplete("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("result1,result2", result.getResult(String.class));
        assertEquals(1, atLeastOnceCount.get());
        assertEquals(1, atMostOnceCount.get());
    }

    @Test
    void testAtMostOnceStepWithCustomRetryStrategy() {
        var executionCount = new AtomicInteger(0);
        var runner = new LocalDurableTestRunner<>(String.class, (input, ctx) -> 
            ctx.step("my-step", String.class, () -> {
                executionCount.incrementAndGet();
                return "result";
            }, StepConfig.builder()
                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .retryStrategy(RetryStrategies.exponentialBackoff(
                    3, 
                    Duration.ofMillis(100), 
                    Duration.ofSeconds(5), 
                    2.0, 
                    JitterStrategy.NONE))
                .build()));

        // Simulate interrupted step
        runner.getStorage().checkpoint("test-arn", "token", java.util.List.of(
            OperationUpdate.builder()
                .id("1")
                .name("my-step")
                .type(OperationType.STEP)
                .action(OperationAction.START)
                .build()));

        var result = runner.runUntilComplete("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get()); // Should execute once after retry
    }
}
