// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.WaitForConditionException;
import software.amazon.lambda.durable.retry.JitterStrategy;

class WaitStrategiesTest {

    // ---- 1.5 Unit tests: builder defaults, backoff, jitter, max attempts ----

    @Test
    void builder_withDefaults_usesExpectedDefaults() {
        // Build with defaults and verify behavior:
        // maxAttempts=60, initialDelay=5s, maxDelay=300s, backoffRate=1.5, jitter=FULL
        var strategy = WaitStrategies.<Integer>builder(state -> true).build();

        // Attempt 0 with FULL jitter: delay should be in [1, 5]
        var decision = strategy.evaluate(42, 0);
        assertTrue(decision.shouldContinue());
        var delay = decision.delay().toSeconds();
        assertTrue(delay >= 1 && delay <= 5, "Default delay at attempt 0 should be in [1, 5], got: " + delay);
    }

    @Test
    void builder_withNoJitter_calculatesExactExponentialBackoff() {
        var strategy = WaitStrategies.<String>builder(state -> true)
                .initialDelay(Duration.ofSeconds(2))
                .backoffRate(2.0)
                .maxDelay(Duration.ofSeconds(60))
                .jitter(JitterStrategy.NONE)
                .build();

        // attempt 0: 2 * 2^0 = 2
        assertContinuePollingWithDelay(strategy.evaluate("x", 0), 2);
        // attempt 1: 2 * 2^1 = 4
        assertContinuePollingWithDelay(strategy.evaluate("x", 1), 4);
        // attempt 2: 2 * 2^2 = 8
        assertContinuePollingWithDelay(strategy.evaluate("x", 2), 8);
        // attempt 3: 2 * 2^3 = 16
        assertContinuePollingWithDelay(strategy.evaluate("x", 3), 16);
    }

    @Test
    void builder_withMaxDelay_capsDelay() {
        var strategy = WaitStrategies.<String>builder(state -> true)
                .initialDelay(Duration.ofSeconds(5))
                .backoffRate(2.0)
                .maxDelay(Duration.ofSeconds(20))
                .jitter(JitterStrategy.NONE)
                .build();

        // attempt 4: min(5 * 2^4 = 80, 20) = 20
        assertContinuePollingWithDelay(strategy.evaluate("x", 4), 20);
    }

    @Test
    void builder_predicateReturnsFalse_stopsPolling() {
        var strategy = WaitStrategies.<Integer>builder(state -> state < 10)
                .jitter(JitterStrategy.NONE)
                .build();

        // State 5 < 10 → continue
        assertTrue(strategy.evaluate(5, 0).shouldContinue());

        // State 10 >= 10 → stop
        assertFalse(strategy.evaluate(10, 0).shouldContinue());
    }

    @Test
    void builder_maxAttemptsExceeded_throwsWaitForConditionException() {
        var strategy = WaitStrategies.<String>builder(state -> true)
                .maxAttempts(3)
                .jitter(JitterStrategy.NONE)
                .build();

        // Attempts 0 and 1 should succeed
        assertTrue(strategy.evaluate("x", 0).shouldContinue());
        assertTrue(strategy.evaluate("x", 1).shouldContinue());

        // Attempt 2 (attempt + 1 >= maxAttempts) should throw
        var exception = assertThrows(WaitForConditionException.class, () -> strategy.evaluate("x", 2));
        assertTrue(exception.getMessage().contains("maximum attempts"));
        assertTrue(exception.getMessage().contains("3"));
    }

    @RepeatedTest(10)
    void builder_fullJitter_producesDelayInExpectedRange() {
        var strategy = WaitStrategies.<String>builder(state -> true)
                .initialDelay(Duration.ofSeconds(10))
                .backoffRate(2.0)
                .maxDelay(Duration.ofSeconds(60))
                .jitter(JitterStrategy.FULL)
                .build();

        // attempt 1: base = min(10 * 2^1, 60) = 20, FULL jitter → [1, 20]
        var decision = strategy.evaluate("x", 1);
        assertTrue(decision.shouldContinue());
        var delay = decision.delay().toSeconds();
        assertTrue(delay >= 1 && delay <= 20, "FULL jitter delay should be in [1, 20], got: " + delay);
    }

    @RepeatedTest(10)
    void builder_halfJitter_producesDelayInExpectedRange() {
        var strategy = WaitStrategies.<String>builder(state -> true)
                .initialDelay(Duration.ofSeconds(10))
                .backoffRate(2.0)
                .maxDelay(Duration.ofSeconds(60))
                .jitter(JitterStrategy.HALF)
                .build();

        // attempt 1: base = 20, HALF jitter → [10, 20]
        var decision = strategy.evaluate("x", 1);
        assertTrue(decision.shouldContinue());
        var delay = decision.delay().toSeconds();
        assertTrue(delay >= 10 && delay <= 20, "HALF jitter delay should be in [10, 20], got: " + delay);
    }

    @Test
    void builder_withNullPredicate_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> WaitStrategies.<String>builder(null));
    }

    @Test
    void builder_withInvalidBackoffRate_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WaitStrategies.<String>builder(s -> true).backoffRate(0.5).build());
    }

    @Test
    void builder_withInvalidMaxAttempts_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WaitStrategies.<String>builder(s -> true).maxAttempts(0).build());
    }

    @Test
    void builder_withNullJitter_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WaitStrategies.<String>builder(s -> true).jitter(null).build());
    }

    @Test
    void builder_predicateCheckedBeforeMaxAttempts() {
        // If predicate returns false, should stop even if attempt >= maxAttempts
        var strategy = WaitStrategies.<Integer>builder(state -> state < 10)
                .maxAttempts(3)
                .jitter(JitterStrategy.NONE)
                .build();

        // State 15 >= 10 → stop, even though attempt 4 >= maxAttempts 3
        assertFalse(strategy.evaluate(15, 4).shouldContinue());
    }

    // ---- 1.6 PBT: Exponential backoff calculation with jitter=NONE ----
    // **Validates: Requirements 2.3, 2.4**

    @RepeatedTest(100)
    void pbt_exponentialBackoffCalculation_noJitter() {
        var random = new Random();

        // Generate random but valid parameters
        long initialDelaySeconds = 1 + random.nextInt(30); // [1, 30]
        double backoffRate = 1.0 + random.nextDouble() * 4.0; // [1.0, 5.0)
        long maxDelaySeconds = initialDelaySeconds + random.nextInt(600); // >= initialDelay
        int attempt = random.nextInt(20); // [0, 19]

        var strategy = WaitStrategies.<String>builder(state -> true)
                .initialDelay(Duration.ofSeconds(initialDelaySeconds))
                .backoffRate(backoffRate)
                .maxDelay(Duration.ofSeconds(maxDelaySeconds))
                .maxAttempts(100) // high enough to not interfere
                .jitter(JitterStrategy.NONE)
                .build();

        var decision = strategy.evaluate("x", attempt);
        assertTrue(decision.shouldContinue());

        var actualDelay = decision.delay().toSeconds();
        double expectedRaw = Math.min(initialDelaySeconds * Math.pow(backoffRate, attempt), maxDelaySeconds);
        long expectedDelay = Math.max(1, Math.round(expectedRaw));

        assertEquals(
                expectedDelay,
                actualDelay,
                String.format(
                        "initialDelay=%d, backoffRate=%.2f, maxDelay=%d, attempt=%d",
                        initialDelaySeconds, backoffRate, maxDelaySeconds, attempt));
    }

    // ---- 1.7 PBT: Max attempts enforcement ----
    // **Validates: Requirements 2.5**

    @RepeatedTest(100)
    void pbt_maxAttemptsEnforcement_throwsWhenExceeded() {
        var random = new Random();

        int maxAttempts = 1 + random.nextInt(50); // [1, 50]
        int attemptOverMax = maxAttempts - 1 + random.nextInt(20);

        var strategy = WaitStrategies.<String>builder(state -> true)
                .maxAttempts(maxAttempts)
                .jitter(JitterStrategy.NONE)
                .build();

        var exception = assertThrows(
                WaitForConditionException.class,
                () -> strategy.evaluate("any-state", attemptOverMax),
                String.format("maxAttempts=%d, attempt=%d should throw", maxAttempts, attemptOverMax));

        assertTrue(exception.getMessage().contains("maximum attempts"));
    }

    // ---- 1.8 PBT: Jitter bounds ----
    // **Validates: Requirements 2.3**

    @RepeatedTest(100)
    void pbt_jitterBounds_noneProducesExactDelay() {
        var random = new Random();
        long delaySeconds = 1 + random.nextInt(300); // [1, 300]

        var strategy = WaitStrategies.<String>builder(state -> true)
                .initialDelay(Duration.ofSeconds(delaySeconds))
                .backoffRate(1.0) // no growth, so delay = initialDelay
                .maxDelay(Duration.ofSeconds(300))
                .maxAttempts(100)
                .jitter(JitterStrategy.NONE)
                .build();

        var decision = strategy.evaluate("x", 0);
        assertTrue(decision.shouldContinue());
        assertEquals(delaySeconds, decision.delay().toSeconds(), "NONE jitter should produce exact delay");
    }

    @RepeatedTest(100)
    void pbt_jitterBounds_fullProducesDelayInRange() {
        var random = new Random();
        long delaySeconds = 2 + random.nextInt(299); // [2, 300] to have meaningful range

        var strategy = WaitStrategies.<String>builder(state -> true)
                .initialDelay(Duration.ofSeconds(delaySeconds))
                .backoffRate(1.0)
                .maxDelay(Duration.ofSeconds(300))
                .maxAttempts(100)
                .jitter(JitterStrategy.FULL)
                .build();

        var decision = strategy.evaluate("x", 0);
        assertTrue(decision.shouldContinue());
        var actualDelay = decision.delay().toSeconds();
        assertTrue(
                actualDelay >= 1 && actualDelay <= delaySeconds,
                String.format("FULL jitter delay should be in [1, %d], got: %d", delaySeconds, actualDelay));
    }

    @RepeatedTest(100)
    void pbt_jitterBounds_halfProducesDelayInRange() {
        var random = new Random();
        long delaySeconds = 2 + random.nextInt(299); // [2, 300]

        var strategy = WaitStrategies.<String>builder(state -> true)
                .initialDelay(Duration.ofSeconds(delaySeconds))
                .backoffRate(1.0)
                .maxDelay(Duration.ofSeconds(300))
                .maxAttempts(100)
                .jitter(JitterStrategy.HALF)
                .build();

        var decision = strategy.evaluate("x", 0);
        assertTrue(decision.shouldContinue());
        var actualDelay = decision.delay().toSeconds();
        long minExpected = Math.max(1, Math.round(delaySeconds / 2.0));
        assertTrue(
                actualDelay >= minExpected && actualDelay <= delaySeconds,
                String.format(
                        "HALF jitter delay should be in [%d, %d], got: %d", minExpected, delaySeconds, actualDelay));
    }

    // ---- Helper ----

    private void assertContinuePollingWithDelay(WaitForConditionDecision decision, long expectedSeconds) {
        assertTrue(decision.shouldContinue());
        assertEquals(expectedSeconds, decision.delay().toSeconds());
    }
}
