// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DistributedMapConfigTest {

    @Test
    void failureCount_setsCount() {
        var config = DistributedMapConfig.CompletionConfig.failureCount(3);

        assertEquals(3, config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
        assertNull(config.minimumSampleSize());
    }

    @Test
    void failureCount_withZero_shouldPass() {
        assertEquals(0, DistributedMapConfig.CompletionConfig.failureCount(0).toleratedFailureCount());
    }

    @Test
    void failurePercentage_setsPercentage() {
        var config = DistributedMapConfig.CompletionConfig.failurePercentage(25);

        assertNull(config.toleratedFailureCount());
        assertEquals(25.0, config.toleratedFailurePercentage());
        assertNull(config.minimumSampleSize());
    }

    @Test
    void failurePercentage_fractional_shouldPass() {
        assertEquals(
                12.5,
                DistributedMapConfig.CompletionConfig.failurePercentage(12.5).toleratedFailurePercentage());
    }

    @Test
    void failurePercentage_withMinimumSampleSize_setsBoth() {
        var config = DistributedMapConfig.CompletionConfig.failurePercentage(25, 10);

        assertEquals(25.0, config.toleratedFailurePercentage());
        assertEquals(10, config.minimumSampleSize());
    }

    @Test
    void failurePercentage_atBoundaries_shouldPass() {
        assertEquals(
                0.0, DistributedMapConfig.CompletionConfig.failurePercentage(0).toleratedFailurePercentage());
        assertEquals(
                100.0,
                DistributedMapConfig.CompletionConfig.failurePercentage(100).toleratedFailurePercentage());
    }

    @Test
    void countAndPercentage_mutuallyExclusive_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> new DistributedMapConfig.CompletionConfig(3, 25.0, null));
        assertEquals(
                "toleratedFailureCount and toleratedFailurePercentage are mutually exclusive", exception.getMessage());
    }

    @Test
    void minimumSampleSize_withoutPercentage_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> new DistributedMapConfig.CompletionConfig(3, null, 5));
        assertEquals("minimumSampleSize is only valid with toleratedFailurePercentage", exception.getMessage());
    }

    @Test
    void completionConfig_onlyMinimumSampleSize_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> new DistributedMapConfig.CompletionConfig(null, null, 5));
        assertEquals("minimumSampleSize is only valid with toleratedFailurePercentage", exception.getMessage());
    }

    @Test
    void failureCount_negative_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapConfig.CompletionConfig.failureCount(-1));
        assertEquals("toleratedFailureCount must be non-negative, got: -1", exception.getMessage());
    }

    @Test
    void failurePercentage_negative_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapConfig.CompletionConfig.failurePercentage(-0.1));
        assertEquals("toleratedFailurePercentage must be between 0 and 100, got: -0.1", exception.getMessage());
    }

    @Test
    void failurePercentage_aboveHundred_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapConfig.CompletionConfig.failurePercentage(100.1));
        assertEquals("toleratedFailurePercentage must be between 0 and 100, got: 100.1", exception.getMessage());
    }

    @Test
    void failurePercentage_nan_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapConfig.CompletionConfig.failurePercentage(Double.NaN));
        assertEquals("toleratedFailurePercentage must be a finite number, got: NaN", exception.getMessage());
    }

    @Test
    void failurePercentage_infinite_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapConfig.CompletionConfig.failurePercentage(Double.POSITIVE_INFINITY));
        assertEquals("toleratedFailurePercentage must be a finite number, got: Infinity", exception.getMessage());
    }

    @Test
    void minimumSampleSize_belowOne_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapConfig.CompletionConfig.failurePercentage(25, 0));
        assertEquals("minimumSampleSize must be at least 1, got: 0", exception.getMessage());
    }

    @Test
    void emptyCompletionConfig_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> new DistributedMapConfig.CompletionConfig(null, null, null));
        assertEquals(
                "a completion config must set toleratedFailureCount or toleratedFailurePercentage",
                exception.getMessage());
    }
}
