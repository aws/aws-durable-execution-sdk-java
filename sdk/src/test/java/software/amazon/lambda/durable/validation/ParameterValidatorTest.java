// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ParameterValidatorTest {

    @Test
    void validateDuration_withValidDuration_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateDuration(Duration.ofSeconds(1), "test"));
        assertDoesNotThrow(() -> ParameterValidator.validateDuration(Duration.ofSeconds(10), "test"));
        assertDoesNotThrow(() -> ParameterValidator.validateDuration(Duration.ofMinutes(5), "test"));
    }

    @Test
    void validateDuration_withNullDuration_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateDuration(null, "testParam"));

        assertEquals("testParam cannot be null", exception.getMessage());
    }

    @Test
    void validateDuration_withZeroDuration_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ParameterValidator.validateDuration(Duration.ofSeconds(0), "testParam"));

        assertEquals("testParam must be at least 1 second, got: PT0S", exception.getMessage());
    }

    @Test
    void validateDuration_withSubSecondDuration_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ParameterValidator.validateDuration(Duration.ofMillis(500), "testParam"));

        assertEquals("testParam must be at least 1 second, got: PT0.5S", exception.getMessage());
    }

    @Test
    void validateDuration_withNegativeDuration_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ParameterValidator.validateDuration(Duration.ofSeconds(-5), "testParam"));

        assertEquals("testParam must be at least 1 second, got: PT-5S", exception.getMessage());
    }

    @Test
    void validateOptionalDuration_withNull_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOptionalDuration(null, "test"));
    }

    @Test
    void validateOptionalDuration_withValidDuration_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOptionalDuration(Duration.ofSeconds(1), "test"));
        assertDoesNotThrow(() -> ParameterValidator.validateOptionalDuration(Duration.ofMinutes(5), "test"));
    }

    @Test
    void validateOptionalDuration_withInvalidDuration_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ParameterValidator.validateOptionalDuration(Duration.ofMillis(999), "testParam"));

        assertEquals("testParam must be at least 1 second, got: PT0.999S", exception.getMessage());
    }

    @Test
    void validatePositiveInteger_withValidValue_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validatePositiveInteger(1, "test"));
        assertDoesNotThrow(() -> ParameterValidator.validatePositiveInteger(100, "test"));
    }

    @Test
    void validatePositiveInteger_withNull_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validatePositiveInteger(null, "testParam"));

        assertEquals("testParam cannot be null", exception.getMessage());
    }

    @Test
    void validatePositiveInteger_withZero_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validatePositiveInteger(0, "testParam"));

        assertEquals("testParam must be positive, got: 0", exception.getMessage());
    }

    @Test
    void validatePositiveInteger_withNegative_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validatePositiveInteger(-5, "testParam"));

        assertEquals("testParam must be positive, got: -5", exception.getMessage());
    }

    @Test
    void validateOptionalPositiveInteger_withNull_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOptionalPositiveInteger(null, "test"));
    }

    @Test
    void validateOptionalPositiveInteger_withValidValue_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOptionalPositiveInteger(1, "test"));
        assertDoesNotThrow(() -> ParameterValidator.validateOptionalPositiveInteger(100, "test"));
    }

    @Test
    void validateOptionalPositiveInteger_withInvalidValue_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ParameterValidator.validateOptionalPositiveInteger(0, "testParam"));

        assertEquals("testParam must be positive, got: 0", exception.getMessage());
    }
}
