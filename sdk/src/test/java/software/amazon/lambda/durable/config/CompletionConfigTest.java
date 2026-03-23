// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompletionConfigTest {

    @Test
    void allSuccessful_zeroFailuresTolerated() {
        var config = CompletionConfig.allSuccessful();

        assertNull(config.minSuccessful());
        assertEquals(0, config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void allCompleted_allFieldsNull() {
        var config = CompletionConfig.allCompleted();

        assertNull(config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void firstSuccessful_minSuccessfulIsOne() {
        var config = CompletionConfig.firstSuccessful();

        assertEquals(1, config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void minSuccessful_setsCount() {
        var config = CompletionConfig.minSuccessful(5);

        assertEquals(5, config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void toleratedFailureCount_setsCount() {
        var config = CompletionConfig.toleratedFailureCount(3);

        assertNull(config.minSuccessful());
        assertEquals(3, config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void toleratedFailurePercentage_setsPercentage() {
        var config = CompletionConfig.toleratedFailurePercentage(0.25);

        assertNull(config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertEquals(0.25, config.toleratedFailurePercentage());
    }

    @Test
    void minSuccessful_withZero_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> CompletionConfig.minSuccessful(0));
        assertEquals("minSuccessful must be at least 1, got: 0", exception.getMessage());
    }

    @Test
    void minSuccessful_withNegative_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> CompletionConfig.minSuccessful(-1));
        assertEquals("minSuccessful must be at least 1, got: -1", exception.getMessage());
    }

    @Test
    void toleratedFailureCount_withNegative_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> CompletionConfig.toleratedFailureCount(-1));
        assertEquals("toleratedFailureCount must be non-negative, got: -1", exception.getMessage());
    }

    @Test
    void toleratedFailurePercentage_withNegative_shouldThrow() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> CompletionConfig.toleratedFailurePercentage(-0.1));
        assertEquals("toleratedFailurePercentage must be between 0.0 and 1.0, got: -0.1", exception.getMessage());
    }

    @Test
    void toleratedFailurePercentage_aboveOne_shouldThrow() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> CompletionConfig.toleratedFailurePercentage(1.5));
        assertEquals("toleratedFailurePercentage must be between 0.0 and 1.0, got: 1.5", exception.getMessage());
    }

    @Test
    void toleratedFailurePercentage_atBoundaries_shouldPass() {
        assertDoesNotThrow(() -> CompletionConfig.toleratedFailurePercentage(0.0));
        assertDoesNotThrow(() -> CompletionConfig.toleratedFailurePercentage(1.0));
    }

    @Test
    void toleratedFailureCount_withZero_shouldPass() {
        var config = CompletionConfig.toleratedFailureCount(0);
        assertEquals(0, config.toleratedFailureCount());
    }
}
