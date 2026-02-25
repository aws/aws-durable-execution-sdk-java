// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.StepConfig;

class RetryStrategiesTest {

    @Test
    void testNoRetryPreset() {
        var strategy = RetryStrategies.Presets.NO_RETRY;

        // Should never retry regardless of attempt number
        var decision1 = strategy.makeRetryDecision(new RuntimeException("test"), 0);
        var decision2 = strategy.makeRetryDecision(new RuntimeException("test"), 1);
        var decision3 = strategy.makeRetryDecision(new RuntimeException("test"), 5);

        assertFalse(decision1.shouldRetry());
        assertFalse(decision2.shouldRetry());
        assertFalse(decision3.shouldRetry());
    }

    @Test
    void testDefaultPresetConfiguration() {
        var strategy = RetryStrategies.Presets.DEFAULT;

        // Should retry for first 5 attempts (0-4), fail on 6th (5)
        for (int attempt = 0; attempt < 5; attempt++) {
            var decision = strategy.makeRetryDecision(new RuntimeException("test"), attempt);
            assertTrue(decision.shouldRetry(), "Should retry on attempt " + attempt);
            assertTrue(decision.delay().toSeconds() >= 1, "Delay should be at least 1 second");
        }

        // Should not retry on 6th attempt (attempt number 5)
        var finalDecision = strategy.makeRetryDecision(new RuntimeException("test"), 5);
        assertFalse(finalDecision.shouldRetry());
    }

    @Test
    void testExponentialBackoffDelayCalculation() {
        // Test with no jitter to verify exact calculation
        var strategy = RetryStrategies.exponentialBackoff(
                5, // maxAttempts
                Duration.ofSeconds(2), // initialDelay
                Duration.ofSeconds(60), // maxDelay
                2.0, // backoffRate
                JitterStrategy.NONE // no jitter for predictable testing
                );

        // Verify delay calculation: initialDelay * backoffRate^attemptNumber
        var decision0 = strategy.makeRetryDecision(new RuntimeException("test"), 0);
        assertEquals(2, decision0.delay().toSeconds()); // 2 * 2^0 = 2

        var decision1 = strategy.makeRetryDecision(new RuntimeException("test"), 1);
        assertEquals(4, decision1.delay().toSeconds()); // 2 * 2^1 = 4

        var decision2 = strategy.makeRetryDecision(new RuntimeException("test"), 2);
        assertEquals(8, decision2.delay().toSeconds()); // 2 * 2^2 = 8

        var decision3 = strategy.makeRetryDecision(new RuntimeException("test"), 3);
        assertEquals(16, decision3.delay().toSeconds()); // 2 * 2^3 = 16
    }

    @Test
    void testMaxDelayCapping() {
        var strategy = RetryStrategies.exponentialBackoff(
                10, // maxAttempts
                Duration.ofSeconds(5), // initialDelay
                Duration.ofSeconds(20), // maxDelay (cap at 20 seconds)
                2.0, // backoffRate
                JitterStrategy.NONE // no jitter
                );

        // Should be capped at maxDelay
        var decision = strategy.makeRetryDecision(new RuntimeException("test"), 5);
        assertEquals(20, decision.delay().toSeconds()); // Would be 5 * 2^5 = 160, but capped at 20
    }

    @Test
    void testJitterStrategies() {
        var noneStrategy = RetryStrategies.exponentialBackoff(
                5, Duration.ofSeconds(10), Duration.ofSeconds(60), 2.0, JitterStrategy.NONE);

        var fullStrategy = RetryStrategies.exponentialBackoff(
                5, Duration.ofSeconds(10), Duration.ofSeconds(60), 2.0, JitterStrategy.FULL);

        var halfStrategy = RetryStrategies.exponentialBackoff(
                5, Duration.ofSeconds(10), Duration.ofSeconds(60), 2.0, JitterStrategy.HALF);

        // Test multiple times due to randomness
        for (int i = 0; i < 10; i++) {
            var noneDecision = noneStrategy.makeRetryDecision(new RuntimeException("test"), 1);
            var fullDecision = fullStrategy.makeRetryDecision(new RuntimeException("test"), 1);
            var halfDecision = halfStrategy.makeRetryDecision(new RuntimeException("test"), 1);

            // NONE should always be exactly 20 (10 * 2^1)
            assertEquals(20, noneDecision.delay().toSeconds());

            // FULL should be between 1 and 20 (0 to baseDelay, minimum 1)
            var fullDelay = fullDecision.delay().toSeconds();
            assertTrue(fullDelay >= 1 && fullDelay <= 20);

            // HALF should be between 10 and 20 (baseDelay/2 to baseDelay)
            var halfDelay = halfDecision.delay().toSeconds();
            assertTrue(halfDelay >= 10 && halfDelay <= 20);
        }
    }

    @Test
    void testMinimumDelayOfOneSecond() {
        // Test that delays are properly calculated with 1-second minimum
        var strategy = RetryStrategies.exponentialBackoff(
                5, Duration.ofSeconds(1), Duration.ofSeconds(60), 1.0, JitterStrategy.FULL);

        var decision = strategy.makeRetryDecision(new RuntimeException("test"), 0);
        assertTrue(decision.delay().toSeconds() >= 1, "Delay should be at least 1 second");
    }

    @Test
    void testFixedDelayStrategy() {
        var strategy = RetryStrategies.fixedDelay(3, Duration.ofSeconds(5));

        // Should retry with fixed delay for first 2 attempts
        var decision1 = strategy.makeRetryDecision(new RuntimeException("test"), 0);
        var decision2 = strategy.makeRetryDecision(new RuntimeException("test"), 1);

        assertTrue(decision1.shouldRetry());
        assertTrue(decision2.shouldRetry());
        assertEquals(5, decision1.delay().toSeconds());
        assertEquals(5, decision2.delay().toSeconds());

        // Should not retry on 3rd attempt
        var decision3 = strategy.makeRetryDecision(new RuntimeException("test"), 2);
        assertFalse(decision3.shouldRetry());
    }

    @Test
    void testInvalidParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RetryStrategies.exponentialBackoff(
                        0, Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, JitterStrategy.NONE));

        assertThrows(
                IllegalArgumentException.class,
                () -> RetryStrategies.exponentialBackoff(
                        5, Duration.ofSeconds(-1), Duration.ofSeconds(10), 2.0, JitterStrategy.NONE));

        assertThrows(
                IllegalArgumentException.class,
                () -> RetryStrategies.exponentialBackoff(
                        5, Duration.ofSeconds(1), Duration.ofSeconds(-1), 2.0, JitterStrategy.NONE));

        assertThrows(
                IllegalArgumentException.class,
                () -> RetryStrategies.exponentialBackoff(
                        5, Duration.ofSeconds(1), Duration.ofSeconds(10), 0, JitterStrategy.NONE));

        assertThrows(IllegalArgumentException.class, () -> RetryStrategies.fixedDelay(0, Duration.ofSeconds(1)));

        assertThrows(IllegalArgumentException.class, () -> RetryStrategies.fixedDelay(5, Duration.ofSeconds(-1)));
    }

    @Test
    void exponentialBackoff_withSubSecondInitialDelay_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> RetryStrategies.exponentialBackoff(
                        3, Duration.ofMillis(500), Duration.ofSeconds(60), 2.0, JitterStrategy.NONE));

        assertTrue(exception.getMessage().contains("initialDelay"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void exponentialBackoff_withSubSecondMaxDelay_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> RetryStrategies.exponentialBackoff(
                        3, Duration.ofSeconds(5), Duration.ofMillis(999), 2.0, JitterStrategy.NONE));

        assertTrue(exception.getMessage().contains("maxDelay"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void exponentialBackoff_withNullInitialDelay_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> RetryStrategies.exponentialBackoff(3, null, Duration.ofSeconds(60), 2.0, JitterStrategy.NONE));

        assertTrue(exception.getMessage().contains("initialDelay"));
        assertTrue(exception.getMessage().contains("cannot be null"));
    }

    @Test
    void exponentialBackoff_withNullMaxDelay_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> RetryStrategies.exponentialBackoff(3, Duration.ofSeconds(5), null, 2.0, JitterStrategy.NONE));

        assertTrue(exception.getMessage().contains("maxDelay"));
        assertTrue(exception.getMessage().contains("cannot be null"));
    }

    @Test
    void fixedDelay_withSubSecondDelay_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> RetryStrategies.fixedDelay(3, Duration.ofMillis(500)));

        assertTrue(exception.getMessage().contains("fixedDelay"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void fixedDelay_withNullDelay_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> RetryStrategies.fixedDelay(3, null));

        assertTrue(exception.getMessage().contains("fixedDelay"));
        assertTrue(exception.getMessage().contains("cannot be null"));
    }

    @Test
    void testStepConfigWithRetryStrategy() {
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

        var decision1 = config1.retryStrategy().makeRetryDecision(new RuntimeException("test"), 0);
        var decision2 = config2.retryStrategy().makeRetryDecision(new RuntimeException("test"), 0);
        var decision3 = config3.retryStrategy().makeRetryDecision(new RuntimeException("test"), 0);

        assertTrue(decision1.shouldRetry());
        assertFalse(decision2.shouldRetry());
        assertTrue(decision3.shouldRetry());
    }

    @Test
    void testRetryStrategyDelayProgression() {
        var strategy = RetryStrategies.exponentialBackoff(
                5, Duration.ofSeconds(2), Duration.ofSeconds(60), 2.0, JitterStrategy.NONE);

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

        assertFalse(decision4.shouldRetry());
    }
}
