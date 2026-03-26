// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;

class WaitStrategiesTest {

    // ---- Presets ----

    @Test
    void presets_default_isNotNull() {
        assertNotNull(WaitStrategies.Presets.DEFAULT);
    }

    @Test
    void defaultStrategy_usesExpectedDefaults() {
        var strategy = WaitStrategies.<Integer>defaultStrategy();
        // With FULL jitter, delay at attempt 0 should be in [1, 5]
        var delay = strategy.evaluate(42, 0).toSeconds();
        assertTrue(delay >= 1 && delay <= 5, "Default delay at attempt 0 should be in [1, 5], got: " + delay);
    }

    // ---- exponentialBackoff factory method ----

    @Test
    void exponentialBackoff_withNoJitter_calculatesExactBackoff() {
        var strategy = WaitStrategies.<String>exponentialBackoff(
                60, Duration.ofSeconds(2), Duration.ofSeconds(60), 2.0, JitterStrategy.NONE);

        assertEquals(Duration.ofSeconds(2), strategy.evaluate("x", 1));
        assertEquals(Duration.ofSeconds(4), strategy.evaluate("x", 2));
        assertEquals(Duration.ofSeconds(8), strategy.evaluate("x", 3));
        assertEquals(Duration.ofSeconds(16), strategy.evaluate("x", 4));
    }

    @Test
    void exponentialBackoff_capsAtMaxDelay() {
        var strategy = WaitStrategies.<String>exponentialBackoff(
                60, Duration.ofSeconds(5), Duration.ofSeconds(20), 2.0, JitterStrategy.NONE);

        // attempt 4: min(5 * 2^4 = 80, 20) = 20
        assertEquals(Duration.ofSeconds(20), strategy.evaluate("x", 4));
    }

    @Test
    void exponentialBackoff_maxAttemptsExceeded_throwsException() {
        var strategy = WaitStrategies.<String>exponentialBackoff(
                3, Duration.ofSeconds(5), Duration.ofSeconds(300), 1.5, JitterStrategy.NONE);

        assertEquals(Duration.ofSeconds(5), strategy.evaluate("x", 1));
        assertEquals(Duration.ofSeconds(8), strategy.evaluate("x", 2));

        var exception = assertThrows(WaitForConditionFailedException.class, () -> strategy.evaluate("x", 3));
        assertTrue(exception.getMessage().contains("maximum attempts"));
        assertTrue(exception.getMessage().contains("3"));
    }

    @RepeatedTest(10)
    void exponentialBackoff_fullJitter_producesDelayInExpectedRange() {
        var strategy = WaitStrategies.<String>exponentialBackoff(
                60, Duration.ofSeconds(10), Duration.ofSeconds(60), 2.0, JitterStrategy.FULL);

        // attempt 1: base = min(10 * 2^1, 60) = 20, FULL jitter -> [1, 20]
        var delay = strategy.evaluate("x", 1).toSeconds();
        assertTrue(delay >= 1 && delay <= 20, "FULL jitter delay should be in [1, 20], got: " + delay);
    }

    @RepeatedTest(10)
    void exponentialBackoff_halfJitter_producesDelayInExpectedRange() {
        var strategy = WaitStrategies.<String>exponentialBackoff(
                60, Duration.ofSeconds(10), Duration.ofSeconds(60), 2.0, JitterStrategy.HALF);

        // attempt 1: base = 20, HALF jitter -> [10, 20]
        var delay = strategy.evaluate("x", 2).toSeconds();
        assertTrue(delay >= 10 && delay <= 20, "HALF jitter delay should be in [10, 20], got: " + delay);
    }

    // ---- fixedDelay factory method ----

    @Test
    void fixedDelay_returnsConstantDelay() {
        var strategy = WaitStrategies.<String>fixedDelay(10, Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(5), strategy.evaluate("x", 0));
        assertEquals(Duration.ofSeconds(5), strategy.evaluate("y", 5));
    }

    @Test
    void fixedDelay_maxAttemptsExceeded_throwsException() {
        var strategy = WaitStrategies.<String>fixedDelay(3, Duration.ofSeconds(5));
        assertThrows(WaitForConditionFailedException.class, () -> strategy.evaluate("x", 3));
    }

    // ---- Validation ----

    @Test
    void exponentialBackoff_withInvalidBackoffRate_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WaitStrategies.<String>exponentialBackoff(
                        60, Duration.ofSeconds(5), Duration.ofSeconds(300), 0.5, JitterStrategy.NONE));
    }

    @Test
    void exponentialBackoff_withInvalidMaxAttempts_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WaitStrategies.<String>exponentialBackoff(
                        0, Duration.ofSeconds(5), Duration.ofSeconds(300), 1.5, JitterStrategy.NONE));
    }

    @Test
    void exponentialBackoff_withNullJitter_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WaitStrategies.<String>exponentialBackoff(
                        60, Duration.ofSeconds(5), Duration.ofSeconds(300), 1.5, null));
    }

    // ---- Exponential backoff calculation with jitter=NONE ----

    @RepeatedTest(100)
    void exponentialBackoffCalculation_noJitter() {
        var random = new Random();

        long initialDelaySeconds = 1 + random.nextInt(30);
        double backoffRate = 1.0 + random.nextDouble() * 4.0;
        long maxDelaySeconds = initialDelaySeconds + random.nextInt(600);
        int attempt = random.nextInt(20) + 1;

        var strategy = WaitStrategies.<String>exponentialBackoff(
                100,
                Duration.ofSeconds(initialDelaySeconds),
                Duration.ofSeconds(maxDelaySeconds),
                backoffRate,
                JitterStrategy.NONE);

        var delay = strategy.evaluate("x", attempt);
        var actualDelay = delay.toSeconds();
        double expectedRaw = Math.min(initialDelaySeconds * Math.pow(backoffRate, attempt - 1), maxDelaySeconds);
        long expectedDelay = Math.max(1, Math.round(expectedRaw));

        assertEquals(
                expectedDelay,
                actualDelay,
                String.format(
                        "initialDelay=%d, backoffRate=%.2f, maxDelay=%d, attempt=%d",
                        initialDelaySeconds, backoffRate, maxDelaySeconds, attempt));
    }

    // ---- Max attempts enforcement ----

    @RepeatedTest(100)
    void maxAttemptsEnforcement_throwsWhenExceeded() {
        var random = new Random();

        int maxAttempts = 1 + random.nextInt(50);
        int attemptOverMax = maxAttempts + random.nextInt(20);

        var strategy = WaitStrategies.<String>exponentialBackoff(
                maxAttempts, Duration.ofSeconds(5), Duration.ofSeconds(300), 1.5, JitterStrategy.NONE);

        var exception = assertThrows(
                WaitForConditionFailedException.class,
                () -> strategy.evaluate("any-state", attemptOverMax),
                String.format("maxAttempts=%d, attempt=%d should throw", maxAttempts, attemptOverMax));

        assertTrue(exception.getMessage().contains("maximum attempts"));
    }

    // ---- Jitter bounds ----

    @RepeatedTest(100)
    void jitterBounds_noneProducesExactDelay() {
        var random = new Random();
        long delaySeconds = 1 + random.nextInt(300);

        var strategy = WaitStrategies.<String>exponentialBackoff(
                100, Duration.ofSeconds(delaySeconds), Duration.ofSeconds(300), 1.0, JitterStrategy.NONE);

        var delay = strategy.evaluate("x", 1);
        assertEquals(delaySeconds, delay.toSeconds(), "NONE jitter should produce exact delay");
    }

    @RepeatedTest(100)
    void jitterBounds_fullProducesDelayInRange() {
        var random = new Random();
        long delaySeconds = 2 + random.nextInt(299);

        var strategy = WaitStrategies.<String>exponentialBackoff(
                100, Duration.ofSeconds(delaySeconds), Duration.ofSeconds(300), 1.0, JitterStrategy.FULL);

        var delay = strategy.evaluate("x", 1);
        var actualDelay = delay.toSeconds();
        assertTrue(
                actualDelay >= 1 && actualDelay <= delaySeconds,
                String.format("FULL jitter delay should be in [1, %d], got: %d", delaySeconds, actualDelay));
    }

    @RepeatedTest(100)
    void jitterBounds_halfProducesDelayInRange() {
        var random = new Random();
        long delaySeconds = 2 + random.nextInt(299);

        var strategy = WaitStrategies.<String>exponentialBackoff(
                100, Duration.ofSeconds(delaySeconds), Duration.ofSeconds(300), 1.0, JitterStrategy.HALF);

        var delay = strategy.evaluate("x", 1);
        var actualDelay = delay.toSeconds();
        long minExpected = Math.max(1, Math.round(delaySeconds / 2.0));
        assertTrue(
                actualDelay >= minExpected && actualDelay <= delaySeconds,
                String.format(
                        "HALF jitter delay should be in [%d, %d], got: %d", minExpected, delaySeconds, actualDelay));
    }
}
