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

    @Test
    void validateOperationName_withNull_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName(null));
    }

    @Test
    void validateOperationName_withValidName_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("test"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("my-operation"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation_123"));
    }

    @Test
    void validateOperationName_withEmptyString_shouldThrow() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> ParameterValidator.validateOperationName(""));

        assertEquals("Operation name cannot be empty", exception.getMessage());
    }

    @Test
    void validateOperationName_withMaxLength_shouldPass() {
        var name = "a".repeat(ParameterValidator.MAX_OPERATION_NAME_LENGTH);
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName(name));
    }

    @Test
    void validateOperationName_exceedingMaxLength_shouldThrow() {
        var name = "a".repeat(ParameterValidator.MAX_OPERATION_NAME_LENGTH + 1);
        var exception =
                assertThrows(IllegalArgumentException.class, () -> ParameterValidator.validateOperationName(name));

        assertEquals(
                "Operation name must be less than " + ParameterValidator.MAX_OPERATION_NAME_LENGTH
                        + " characters, got: " + name,
                exception.getMessage());
    }

    @Test
    void validateOperationName_withCustomMaxLength_withNull_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName(null, 100));
    }

    @Test
    void validateOperationName_withCustomMaxLength_withValidName_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("test", 100));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("a".repeat(100), 100));
    }

    @Test
    void validateOperationName_withCustomMaxLength_withEmptyString_shouldThrow() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("", 100));

        assertEquals("Operation name cannot be empty", exception.getMessage());
    }

    @Test
    void validateOperationName_withCustomMaxLength_exceedingLimit_shouldThrow() {
        var customMaxLength = 50;
        var name = "a".repeat(customMaxLength + 1);
        var exception = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateOperationName(name, customMaxLength));

        assertEquals(
                "Operation name must be less than " + customMaxLength + " characters, got: " + name,
                exception.getMessage());
    }

    @Test
    void validateOperationName_withCustomMaxLength_atExactLimit_shouldPass() {
        var customMaxLength = 50;
        var name = "a".repeat(customMaxLength);
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName(name, customMaxLength));
    }

    @Test
    void validateOperationName_withSpecialCharacters_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation-name"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation_name"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation.name"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation:name"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation/name"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation@name"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation#name"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation$name"));
    }

    @Test
    void validateOperationName_withUnicodeCharacters_shouldThrow() {
        var exception1 =
                assertThrows(IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("操作名称"));
        assertEquals("Operation name must contain only printable ASCII characters, got: 操作名称", exception1.getMessage());

        var exception2 = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("opération"));
        assertEquals(
                "Operation name must contain only printable ASCII characters, got: opération", exception2.getMessage());

        var exception3 = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("операция"));
        assertEquals(
                "Operation name must contain only printable ASCII characters, got: операция", exception3.getMessage());
    }

    @Test
    void validateOperationName_withWhitespace_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation name"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName(" operation"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("operation "));
    }

    @Test
    void validateOperationName_withControlCharacters_shouldThrow() {
        var exception1 = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("operation\nname"));
        assertEquals(
                "Operation name must contain only printable ASCII characters, got: operation\nname",
                exception1.getMessage());

        var exception2 = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("operation\tname"));
        assertEquals(
                "Operation name must contain only printable ASCII characters, got: operation\tname",
                exception2.getMessage());

        var exception3 = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("operation\rname"));
        assertEquals(
                "Operation name must contain only printable ASCII characters, got: operation\rname",
                exception3.getMessage());
    }

    @Test
    void validateOperationName_withNonPrintableASCII_shouldThrow() {
        // Test character below printable range (0x1F)
        var exception1 = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("test\u001Fname"));
        assertEquals(
                "Operation name must contain only printable ASCII characters, got: test\u001Fname",
                exception1.getMessage());

        // Test character above printable range (0x7F - DEL)
        var exception2 = assertThrows(
                IllegalArgumentException.class, () -> ParameterValidator.validateOperationName("test\u007Fname"));
        assertEquals(
                "Operation name must contain only printable ASCII characters, got: test\u007Fname",
                exception2.getMessage());
    }

    @Test
    void validateOperationName_withPrintableASCIIBoundaries_shouldPass() {
        // Test lower boundary (0x20 - space)
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName(" "));

        // Test upper boundary (0x7E - tilde)
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("~"));

        // Test all printable ASCII characters
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName(
                "!\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"));
    }

    @Test
    void validateOperationName_withSingleCharacter_shouldPass() {
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("a"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("1"));
        assertDoesNotThrow(() -> ParameterValidator.validateOperationName("-"));
    }
}
