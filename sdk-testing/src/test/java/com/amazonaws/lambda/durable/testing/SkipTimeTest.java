// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.amazonaws.lambda.durable.retry.RetryStrategies;
import org.junit.jupiter.api.Test;

class SkipTimeTest {

    static class TestInput {
        public String value;
        
        public TestInput() {}
        
        public TestInput(String value) { 
            this.value = value; 
        }
    }

    @Test
    void testSkipTime() {
        var runner = new LocalDurableTestRunner<>(TestInput.class, (input, context) -> {
            var step1 = context.step("step-1", String.class, () -> "step1-done");
            context.wait(Duration.ofMinutes(5));
            var step2 = context.step("step-2", String.class, () -> "step2-done");
            return step1 + "+" + step2;
        });

        runner.setSkipTime(true);

        // First run - should execute until wait and return PENDING
        var result = runner.runUntilComplete(new TestInput("test"));
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("step1-done+step2-done", result.getResult(String.class));
        assertEquals(3, result.getSucceededOperations().size());
    }

    @Test
    void testManualTimeControl() {
        var runner = new LocalDurableTestRunner<>(TestInput.class, (input, context) -> {
            var step1 = context.step("step-1", String.class, () -> "step1-done");
            context.wait(Duration.ofMinutes(5));
            var step2 = context.step("step-2", String.class, () -> "step2-done");
            return step1 + "+" + step2;
        });
        
        runner.setSkipTime(false);
        
        // First run - should execute until wait and return PENDING
        var result1 = runner.runUntilComplete(new TestInput("test"));
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());
        
        // Manually advance time
        runner.advanceTime();
        
        // Second run - should complete after wait
        var result2 = runner.runUntilComplete(new TestInput("test"));
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("step1-done+step2-done", result2.getResult(String.class));
    }
    
    @Test
    void testManualTimeControlWithRetry() {
        var attempts = new AtomicInteger(0);
        
        var runner = new LocalDurableTestRunner<TestInput, String>(TestInput.class, (input, context) -> {
            return context.step("flaky-step", String.class, () -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new RuntimeException("Transient failure");
                }
                return "success";
            }, StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                .build());
        });
        
        runner.setSkipTime(false);
        
        // First run - should fail and return PENDING (waiting for retry)
        var result1 = runner.runUntilComplete(new TestInput("test"));
        System.out.println("First run status: " + result1.getStatus());
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());
        assertEquals(1, result1.getOperations().get(0).getStepDetails().attempt());
        
        // Manually advance time (makes retry ready)
        runner.advanceTime();
        
        // Second run - should retry and fail again, return PENDING for next retry
        var result2 = runner.runUntilComplete(new TestInput("test"));
        assertEquals(ExecutionStatus.PENDING, result2.getStatus());
        assertEquals(2, result2.getOperations().get(0).getStepDetails().attempt());
        
        // Advance time again
        runner.advanceTime();
        
        // Third run - should succeed
        var result3 = runner.runUntilComplete(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result3.getStatus());
        assertEquals("success", result3.getResult(String.class));
    }
}
