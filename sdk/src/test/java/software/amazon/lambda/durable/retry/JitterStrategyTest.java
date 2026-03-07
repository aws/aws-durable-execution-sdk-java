// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JitterStrategyTest {

    private static final int ITERATIONS = 100;

    @Test
    void none_returnsExactBaseDelay() {
        assertEquals(100.0, JitterStrategy.NONE.apply(100.0));
        assertEquals(0.0, JitterStrategy.NONE.apply(0.0));
        assertEquals(1.5, JitterStrategy.NONE.apply(1.5));
    }

    @Test
    void full_returnsBetweenZeroAndBaseDelay() {
        double baseDelay = 1000.0;
        for (int i = 0; i < ITERATIONS; i++) {
            double result = JitterStrategy.FULL.apply(baseDelay);
            assertTrue(result >= 0, "FULL jitter should be >= 0, got: " + result);
            assertTrue(result <= baseDelay, "FULL jitter should be <= baseDelay, got: " + result);
        }
    }

    @Test
    void half_returnsBetweenHalfAndBaseDelay() {
        double baseDelay = 1000.0;
        for (int i = 0; i < ITERATIONS; i++) {
            double result = JitterStrategy.HALF.apply(baseDelay);
            assertTrue(result >= baseDelay / 2, "HALF jitter should be >= baseDelay/2, got: " + result);
            assertTrue(result <= baseDelay, "HALF jitter should be <= baseDelay, got: " + result);
        }
    }

    @Test
    void full_withZeroBaseDelay_returnsZero() {
        assertEquals(0.0, JitterStrategy.FULL.apply(0.0));
    }

    @Test
    void half_withZeroBaseDelay_returnsZero() {
        assertEquals(0.0, JitterStrategy.HALF.apply(0.0));
    }

    @Test
    void full_producesVariation() {
        double baseDelay = 1000.0;
        boolean sawDifferentValues = false;
        double firstResult = JitterStrategy.FULL.apply(baseDelay);
        for (int i = 0; i < ITERATIONS; i++) {
            if (JitterStrategy.FULL.apply(baseDelay) != firstResult) {
                sawDifferentValues = true;
                break;
            }
        }
        assertTrue(sawDifferentValues, "FULL jitter should produce varying results");
    }

    @Test
    void half_producesVariation() {
        double baseDelay = 1000.0;
        boolean sawDifferentValues = false;
        double firstResult = JitterStrategy.HALF.apply(baseDelay);
        for (int i = 0; i < ITERATIONS; i++) {
            if (JitterStrategy.HALF.apply(baseDelay) != firstResult) {
                sawDifferentValues = true;
                break;
            }
        }
        assertTrue(sawDifferentValues, "HALF jitter should produce varying results");
    }

    @Test
    void allStrategies_withSmallBaseDelay_returnNonNegative() {
        double baseDelay = 0.001;
        for (int i = 0; i < ITERATIONS; i++) {
            assertTrue(JitterStrategy.NONE.apply(baseDelay) >= 0);
            assertTrue(JitterStrategy.FULL.apply(baseDelay) >= 0);
            assertTrue(JitterStrategy.HALF.apply(baseDelay) >= 0);
        }
    }

    @Test
    void enumValues_containsAllStrategies() {
        var values = JitterStrategy.values();
        assertEquals(3, values.length);
        assertEquals(JitterStrategy.NONE, JitterStrategy.valueOf("NONE"));
        assertEquals(JitterStrategy.FULL, JitterStrategy.valueOf("FULL"));
        assertEquals(JitterStrategy.HALF, JitterStrategy.valueOf("HALF"));
    }
}
