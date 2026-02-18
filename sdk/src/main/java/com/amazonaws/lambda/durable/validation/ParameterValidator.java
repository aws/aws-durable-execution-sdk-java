// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.validation;

import java.time.Duration;

/**
 * Utility class for validating input parameters in the Durable Execution SDK.
 *
 * <p>Provides common validation methods to ensure consistent error messages and validation logic across the SDK.
 */
public final class ParameterValidator {

    private static final long MIN_DURATION_SECONDS = 1;

    private ParameterValidator() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates that a duration is at least 1 second.
     *
     * @param duration the duration to validate
     * @param parameterName the name of the parameter (for error messages)
     * @throws IllegalArgumentException if duration is null or less than 1 second
     */
    public static void validateDuration(Duration duration, String parameterName) {
        if (duration == null) {
            throw new IllegalArgumentException(parameterName + " cannot be null");
        }
        if (duration.toSeconds() < MIN_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    parameterName + " must be at least " + MIN_DURATION_SECONDS + " second, got: " + duration);
        }
    }

    /**
     * Validates that an optional duration (if provided) is at least 1 second.
     *
     * @param duration the duration to validate (can be null)
     * @param parameterName the name of the parameter (for error messages)
     * @throws IllegalArgumentException if duration is non-null and less than 1 second
     */
    public static void validateOptionalDuration(Duration duration, String parameterName) {
        if (duration != null && duration.toSeconds() < MIN_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    parameterName + " must be at least " + MIN_DURATION_SECONDS + " second, got: " + duration);
        }
    }

    /**
     * Validates that an integer value is positive (greater than 0).
     *
     * @param value the value to validate
     * @param parameterName the name of the parameter (for error messages)
     * @throws IllegalArgumentException if value is null or not positive
     */
    public static void validatePositiveInteger(Integer value, String parameterName) {
        if (value == null) {
            throw new IllegalArgumentException(parameterName + " cannot be null");
        }
        if (value <= 0) {
            throw new IllegalArgumentException(parameterName + " must be positive, got: " + value);
        }
    }

    /**
     * Validates that an optional integer value (if provided) is positive (greater than 0).
     *
     * @param value the value to validate (can be null)
     * @param parameterName the name of the parameter (for error messages)
     * @throws IllegalArgumentException if value is non-null and not positive
     */
    public static void validateOptionalPositiveInteger(Integer value, String parameterName) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(parameterName + " must be positive, got: " + value);
        }
    }
}
