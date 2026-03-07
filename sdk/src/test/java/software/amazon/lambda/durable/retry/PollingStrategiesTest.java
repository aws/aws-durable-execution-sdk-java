// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PollingStrategiesTest {

    private static final Duration DEFAULT_MAX = Duration.ofSeconds(10);

    @Test
    void defaultPreset_usesExpectedConfiguration() {
        var strategy = PollingStrategies.Presets.DEFAULT;

        // Default: base=1000ms, rate=2.0, jitter=FULL, maxInterval=10s
        // With FULL jitter, delay should be between 0 and base*rate^attempt
        for (int i = 0; i < 10; i++) {
            var delay = strategy.computeDelay(0);
            assertTrue(
                    delay.toMillis() >= 0 && delay.toMillis() <= 1000,
                    "Attempt 0 delay should be in [0, 1000]ms, got " + delay.toMillis());
        }
    }

    @Test
    void fixedDelay_computesFixedDelay() {
        var strategy = PollingStrategies.fixedDelay(Duration.ofMillis(500));

        assertEquals(Duration.ofMillis(500), strategy.computeDelay(0));
        assertEquals(Duration.ofMillis(500), strategy.computeDelay(1));
        assertEquals(Duration.ofMillis(500), strategy.computeDelay(2));
        assertEquals(Duration.ofMillis(500), strategy.computeDelay(3));
    }

    @Test
    void exponentialBackoff_withNoJitter_computesDeterministicDelays() {
        var strategy =
                PollingStrategies.exponentialBackoff(Duration.ofMillis(100), 2.0, JitterStrategy.NONE, DEFAULT_MAX);

        // delay = base * rate^attempt
        assertEquals(100, strategy.computeDelay(0).toMillis()); // 100 * 2^0
        assertEquals(200, strategy.computeDelay(1).toMillis()); // 100 * 2^1
        assertEquals(400, strategy.computeDelay(2).toMillis()); // 100 * 2^2
        assertEquals(800, strategy.computeDelay(3).toMillis()); // 100 * 2^3
        assertEquals(1600, strategy.computeDelay(4).toMillis()); // 100 * 2^4
    }

    @Test
    void exponentialBackoff_withNoJitter_differentBackoffRates() {
        var strategy =
                PollingStrategies.exponentialBackoff(Duration.ofMillis(50), 3.0, JitterStrategy.NONE, DEFAULT_MAX);

        assertEquals(50, strategy.computeDelay(0).toMillis()); // 50 * 3^0
        assertEquals(150, strategy.computeDelay(1).toMillis()); // 50 * 3^1
        assertEquals(450, strategy.computeDelay(2).toMillis()); // 50 * 3^2
        assertEquals(1350, strategy.computeDelay(3).toMillis()); // 50 * 3^3
    }

    @Test
    void exponentialBackoff_withFullJitter_delayInExpectedRange() {
        var strategy =
                PollingStrategies.exponentialBackoff(Duration.ofMillis(100), 2.0, JitterStrategy.FULL, DEFAULT_MAX);

        for (int i = 0; i < 20; i++) {
            var delay0 = strategy.computeDelay(0).toMillis();
            assertTrue(
                    delay0 >= 0 && delay0 <= 100, "Attempt 0 with FULL jitter should be in [0, 100]ms, got " + delay0);

            var delay2 = strategy.computeDelay(2).toMillis();
            assertTrue(
                    delay2 >= 0 && delay2 <= 400, "Attempt 2 with FULL jitter should be in [0, 400]ms, got " + delay2);
        }
    }

    @Test
    void exponentialBackoff_withHalfJitter_delayInExpectedRange() {
        var strategy =
                PollingStrategies.exponentialBackoff(Duration.ofMillis(100), 2.0, JitterStrategy.HALF, DEFAULT_MAX);

        for (int i = 0; i < 20; i++) {
            var delay0 = strategy.computeDelay(0).toMillis();
            assertTrue(
                    delay0 >= 50 && delay0 <= 100,
                    "Attempt 0 with HALF jitter should be in [50, 100]ms, got " + delay0);

            var delay2 = strategy.computeDelay(2).toMillis();
            assertTrue(
                    delay2 >= 200 && delay2 <= 400,
                    "Attempt 2 with HALF jitter should be in [200, 400]ms, got " + delay2);
        }
    }

    // --- maxInterval tests ---

    @Test
    void exponentialBackoff_withMaxInterval_capsDelay() {
        // base=100ms, rate=2.0, NONE jitter → deterministic: 100, 200, 400, 800, 1600...
        // maxInterval=500ms → should cap at 500
        var strategy = PollingStrategies.exponentialBackoff(
                Duration.ofMillis(100), 2.0, JitterStrategy.NONE, Duration.ofMillis(500));

        assertEquals(100, strategy.computeDelay(0).toMillis()); // 100 < 500
        assertEquals(200, strategy.computeDelay(1).toMillis()); // 200 < 500
        assertEquals(400, strategy.computeDelay(2).toMillis()); // 400 < 500
        assertEquals(500, strategy.computeDelay(3).toMillis()); // 800 → capped to 500
        assertEquals(500, strategy.computeDelay(4).toMillis()); // 1600 → capped to 500
        assertEquals(500, strategy.computeDelay(10).toMillis()); // huge → capped to 500
    }

    @Test
    void exponentialBackoff_withMaxInterval_capsAfterJitter() {
        // With FULL jitter, delay = random(0, base*rate^attempt), then capped by maxInterval
        // base=100ms, rate=2.0, maxInterval=300ms
        var strategy = PollingStrategies.exponentialBackoff(
                Duration.ofMillis(100), 2.0, JitterStrategy.FULL, Duration.ofMillis(300));

        for (int i = 0; i < 50; i++) {
            // At attempt 5, uncapped range would be [0, 3200]ms
            // After maxInterval cap, should never exceed 300ms
            var delay = strategy.computeDelay(i).toMillis();
            assertTrue(delay >= 0 && delay <= 300, "Delay should be capped at maxInterval=300ms, got " + delay + "ms");
        }
    }

    @Test
    void exponentialBackoff_withMaxInterval_defaultPresetCapsAt10Seconds() {
        var strategy = PollingStrategies.Presets.DEFAULT;

        // Default: base=1000ms, rate=2.0, FULL jitter, maxInterval=10s
        // At high attempts, uncapped delay would be huge, but should cap at 10s
        for (int i = 0; i < 20; i++) {
            var delay = strategy.computeDelay(i).toMillis();
            assertTrue(delay <= 10_000, "Default preset should cap at 10s maxInterval, got " + delay + "ms");
        }
    }

    // --- Parameter validation tests ---

    @Test
    void exponentialBackoff_nullBaseInterval_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> PollingStrategies.exponentialBackoff(null, 2.0, JitterStrategy.NONE, DEFAULT_MAX));
    }

    @Test
    void exponentialBackoff_nullJitter_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> PollingStrategies.exponentialBackoff(Duration.ofMillis(100), 2.0, null, DEFAULT_MAX));
    }

    @Test
    void exponentialBackoff_nullMaxInterval_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> PollingStrategies.exponentialBackoff(Duration.ofMillis(100), 2.0, JitterStrategy.NONE, null));
    }

    @Test
    void exponentialBackoff_zeroBackoffRate_throwsException() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(100), 0.0, JitterStrategy.NONE, DEFAULT_MAX));

        assertEquals("backoffRate must be positive", exception.getMessage());
    }

    @Test
    void exponentialBackoff_negativeBackoffRate_throwsException() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(100), -1.0, JitterStrategy.NONE, DEFAULT_MAX));

        assertEquals("backoffRate must be positive", exception.getMessage());
    }

    @Test
    void exponentialBackoff_zeroBaseInterval_throwsException() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> PollingStrategies.exponentialBackoff(Duration.ZERO, 2.0, JitterStrategy.NONE, DEFAULT_MAX));

        assertEquals("baseInterval must be positive", exception.getMessage());
    }

    @Test
    void exponentialBackoff_negativeBaseInterval_throwsException() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(-100), 2.0, JitterStrategy.NONE, DEFAULT_MAX));

        assertEquals("baseInterval must be positive", exception.getMessage());
    }

    @Test
    void exponentialBackoff_zeroMaxInterval_throwsException() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(100), 2.0, JitterStrategy.NONE, Duration.ZERO));

        assertEquals("maxInterval must be positive", exception.getMessage());
    }

    @Test
    void exponentialBackoff_negativeMaxInterval_throwsException() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(100), 2.0, JitterStrategy.NONE, Duration.ofMillis(-500)));

        assertEquals("maxInterval must be positive", exception.getMessage());
    }

    @Test
    void fixedDelay_nullInterval_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> PollingStrategies.fixedDelay(null));
    }

    @Test
    void fixedDelay_zeroInterval_throwsException() {
        var exception = assertThrows(IllegalArgumentException.class, () -> PollingStrategies.fixedDelay(Duration.ZERO));

        assertEquals("interval must be positive", exception.getMessage());
    }

    @Test
    void fixedDelay_negativeInterval_throwsException() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> PollingStrategies.fixedDelay(Duration.ofMillis(-100)));

        assertEquals("interval must be positive", exception.getMessage());
    }
}
