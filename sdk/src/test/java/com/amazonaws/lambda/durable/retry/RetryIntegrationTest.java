// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for retry strategy functionality. Tests retry strategy configuration, delay progression, and
 * integration with DurableContext.
 */
class RetryIntegrationTest {

    private AtomicInteger callCount;

    @BeforeEach
    void setUp() {
        callCount = new AtomicInteger(0);
    }

    @Test
    void testStepConfigWithRetryStrategy() {
        // Test that we can create and use StepConfig with retry strategies
        var config1 = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                .build();

        var config2 = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();

        var config3 = StepConfig.builder()
                .retryStrategy(RetryStrategies.exponentialBackoff(
                        3, Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, JitterStrategy.NONE))
                .build();

        assertNotNull(config1.retryStrategy());
        assertNotNull(config2.retryStrategy());
        assertNotNull(config3.retryStrategy());

        // Test that the strategies work as expected
        var decision1 = config1.retryStrategy().makeRetryDecision(new RuntimeException("test"), 0);
        var decision2 = config2.retryStrategy().makeRetryDecision(new RuntimeException("test"), 0);
        var decision3 = config3.retryStrategy().makeRetryDecision(new RuntimeException("test"), 0);

        assertTrue(decision1.shouldRetry()); // DEFAULT should retry
        assertFalse(decision2.shouldRetry()); // NO_RETRY should not retry
        assertTrue(decision3.shouldRetry()); // Custom should retry on first attempt
    }

    @Test
    void testRetryStrategyDelayProgression() {
        // Test that exponential backoff produces expected delay progression
        var strategy = RetryStrategies.exponentialBackoff(
                5, // 5 attempts
                Duration.ofSeconds(2), // 2 second initial delay
                Duration.ofSeconds(60), // 60 second max delay
                2.0, // 2x backoff rate
                JitterStrategy.NONE // no jitter for predictable testing
                );

        // Test delay progression: 2, 4, 8, 16 seconds
        var decision0 = strategy.makeRetryDecision(new RuntimeException("test"), 0);
        var decision1 = strategy.makeRetryDecision(new RuntimeException("test"), 1);
        var decision2 = strategy.makeRetryDecision(new RuntimeException("test"), 2);
        var decision3 = strategy.makeRetryDecision(new RuntimeException("test"), 3);
        var decision4 = strategy.makeRetryDecision(new RuntimeException("test"), 4);

        assertTrue(decision0.shouldRetry());
        assertEquals(2, decision0.delay().toSeconds());

        assertTrue(decision1.shouldRetry());
        assertEquals(4, decision1.delay().toSeconds());

        assertTrue(decision2.shouldRetry());
        assertEquals(8, decision2.delay().toSeconds());

        assertTrue(decision3.shouldRetry());
        assertEquals(16, decision3.delay().toSeconds());

        // 5th attempt (index 4) should not retry (maxAttempts = 5)
        assertFalse(decision4.shouldRetry());
    }

    @Test
    // TODO: Implement retry logic in LocalDurableTestRunner
    void testStepWithDefaultRetryStrategy_ShouldRetryOnFailure() {
        // Test handler that uses default retry strategy
        var handler = new DurableHandler<String, String>() {
            @Override
            protected String handleRequest(String input, DurableContext context) {
                var config = StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build();

                return context.step(
                        "failing-step",
                        String.class,
                        () -> {
                            callCount.incrementAndGet();
                            throw new RuntimeException("Simulated failure");
                        },
                        config);
            }
        };

        var runner = new LocalDurableTestRunner<>(String.class, handler::handleRequest);

        // This should trigger retry logic and result in PENDING status
        var result = runner.run("test-input");

        // Should be PENDING due to retry suspension
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Verify the function was called once before retry
        assertEquals(1, callCount.get());
    }

    @Test
    void testStepWithNoRetryStrategy_ShouldFailImmediately() {
        // Test handler that uses no retry strategy
        var handler = new DurableHandler<String, String>() {
            @Override
            protected String handleRequest(String input, DurableContext context) {
                var config = StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build();

                return context.step(
                        "no-retry-step",
                        String.class,
                        () -> {
                            callCount.incrementAndGet();
                            throw new RuntimeException("Simulated failure");
                        },
                        config);
            }
        };

        var runner = new LocalDurableTestRunner<>(String.class, handler::handleRequest);

        // This should fail immediately without retry
        var result = runner.run("test-input");

        // Should be FAILED due to no retry
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // Verify the function was called once
        assertEquals(1, callCount.get());
    }

    @Test
    void testSuccessfulStepWithRetryConfig_ShouldNotTriggerRetry() {
        // Test handler that succeeds with retry config - verifies StepConfig integration
        var handler = new DurableHandler<String, String>() {
            @Override
            protected String handleRequest(String input, DurableContext context) {
                var config = StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build();

                return context.step(
                        "successful-step",
                        String.class,
                        () -> {
                            callCount.incrementAndGet();
                            return "success: " + input;
                        },
                        config);
            }
        };

        var runner = new LocalDurableTestRunner<>(String.class, handler::handleRequest);

        // This should succeed without triggering retry
        var result = runner.run("test-input");

        // Should be SUCCEEDED
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("success: test-input", result.getResult(String.class));

        // Verify the function was called once
        assertEquals(1, callCount.get());
    }
}
