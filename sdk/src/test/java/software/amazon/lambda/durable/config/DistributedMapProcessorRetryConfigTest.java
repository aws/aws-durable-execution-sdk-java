// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DistributedMapProcessorRetryConfigTest {

    @Test
    void build_defaultsAreNull() {
        var retry = DistributedMapProcessorRetryConfig.builder().build();

        assertNull(retry.maxRetryAttempts());
        assertNull(retry.maxRetryDuration());
    }

    @Test
    void maxRetryAttempts_zero_isStored() {
        var retry =
                DistributedMapProcessorRetryConfig.builder().maxRetryAttempts(0).build();

        assertEquals(0, retry.maxRetryAttempts());
    }

    @Test
    void maxRetryAttempts_unlimited_isStored() {
        var retry = DistributedMapProcessorRetryConfig.builder()
                .maxRetryAttempts(DistributedMapProcessorRetryConfig.UNLIMITED)
                .build();

        assertEquals(-1, retry.maxRetryAttempts());
    }

    @Test
    void maxRetryAttempts_otherNegative_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessorRetryConfig.builder().maxRetryAttempts(-2));
        assertEquals(
                "maxRetryAttempts must be non-negative or DistributedMapProcessorRetryConfig.UNLIMITED, got: -2",
                exception.getMessage());
    }

    @Test
    void maxRetryDuration_atBoundaries_shouldPass() {
        assertEquals(
                Duration.ofMinutes(1),
                DistributedMapProcessorRetryConfig.builder()
                        .maxRetryDuration(Duration.ofMinutes(1))
                        .build()
                        .maxRetryDuration());
        assertEquals(
                Duration.ofHours(6),
                DistributedMapProcessorRetryConfig.builder()
                        .maxRetryDuration(Duration.ofHours(6))
                        .build()
                        .maxRetryDuration());
    }

    @Test
    void maxRetryDuration_belowMinimum_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessorRetryConfig.builder().maxRetryDuration(Duration.ofSeconds(59)));
        assertEquals("maxRetryDuration must be between 1 minute and 6 hours, got: 59s", exception.getMessage());
    }

    @Test
    void maxRetryDuration_aboveMaximum_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessorRetryConfig.builder().maxRetryDuration(Duration.ofHours(7)));
        assertEquals("maxRetryDuration must be between 1 minute and 6 hours, got: 25200s", exception.getMessage());
    }
}
