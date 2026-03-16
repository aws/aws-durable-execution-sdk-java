// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ParallelConfigTest {

    @Test
    void defaultValues() {
        var config = ParallelConfig.builder().build();

        assertEquals(-1, config.maxConcurrency());
        assertEquals(-1, config.minSuccessful());
        assertEquals(0, config.toleratedFailureCount());
    }

    @Test
    void builderRoundTrip() {
        var config = ParallelConfig.builder()
                .maxConcurrency(4)
                .minSuccessful(2)
                .toleratedFailureCount(3)
                .build();

        assertEquals(4, config.maxConcurrency());
        assertEquals(2, config.minSuccessful());
        assertEquals(3, config.toleratedFailureCount());
    }

    @Test
    void maxConcurrencyOfOne() {
        var config = ParallelConfig.builder().maxConcurrency(1).build();

        assertEquals(1, config.maxConcurrency());
    }

    @Test
    void minSuccessfulOfZero() {
        var config = ParallelConfig.builder().minSuccessful(0).build();

        assertEquals(0, config.minSuccessful());
    }

    @Test
    void unlimitedConcurrency() {
        var config = ParallelConfig.builder().maxConcurrency(-1).build();

        assertEquals(-1, config.maxConcurrency());
    }

    @Test
    void maxConcurrencyZeroThrows() {
        var builder = ParallelConfig.builder().maxConcurrency(0);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void maxConcurrencyNegativeTwoThrows() {
        var builder = ParallelConfig.builder().maxConcurrency(-2);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void minSuccessfulNegativeTwoThrows() {
        var builder = ParallelConfig.builder().minSuccessful(-2);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void toleratedFailureCountNegativeThrows() {
        var builder = ParallelConfig.builder().toleratedFailureCount(-1);
        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
