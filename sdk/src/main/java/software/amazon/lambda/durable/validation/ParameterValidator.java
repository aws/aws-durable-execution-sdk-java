// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.validation;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for validating input parameters in the Durable Execution SDK.
 *
 * <p>Provides common validation methods to ensure consistent error messages and validation logic across the SDK.
 */
public final class ParameterValidator {

    private static final long MIN_DURATION_SECONDS = 1;
    public static final int MAX_OPERATION_NAME_LENGTH = 256;

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

    public static void validateOperationName(String name) {
        validateOperationName(name, MAX_OPERATION_NAME_LENGTH);
    }

    public static void validateOperationName(String name, int maxLength) {
        if (name == null) {
            // operation name is optional
            return;
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Operation name cannot be empty");
        }
        if (name.length() > maxLength) {
            throw new IllegalArgumentException(
                    "Operation name must be less than " + maxLength + " characters, got: " + name);
        }

        // validate each character is printable ASCII
        for (char c : name.toCharArray()) {
            if (c < 0x20 || c > 0x7e) {
                throw new IllegalArgumentException(
                        "Operation name must contain only printable ASCII characters, got: " + name);
            }
        }
    }

    /** Known unordered map types whose views (keySet, values, entrySet) do not guarantee iteration order. */
    private static final Set<Class<?>> UNORDERED_MAP_TYPES =
            Set.of(HashMap.class, IdentityHashMap.class, WeakHashMap.class, ConcurrentHashMap.class);

    /**
     * Validates that a collection has deterministic iteration order.
     *
     * <p>Rejects known unordered collection types: {@link HashSet} (but not {@link LinkedHashSet}, which has stable
     * insertion-order iteration), and views returned by {@link HashMap}, {@link IdentityHashMap}, {@link WeakHashMap},
     * and {@link ConcurrentHashMap}.
     *
     * @param items the collection to validate
     * @throws IllegalArgumentException if items is null or has non-deterministic iteration order
     */
    public static void validateOrderedCollection(Collection<?> items) {
        if (items == null) {
            throw new IllegalArgumentException("items cannot be null");
        }
        // LinkedHashSet extends HashSet but has stable insertion-order iteration — allow it
        if (items instanceof LinkedHashSet) {
            return;
        }
        if (items instanceof HashSet || isUnorderedMapView(items)) {
            throw new IllegalArgumentException("items must have deterministic iteration order");
        }
    }

    private static boolean isUnorderedMapView(Collection<?> collection) {
        var enclosing = collection.getClass().getEnclosingClass();
        return enclosing != null && UNORDERED_MAP_TYPES.contains(enclosing);
    }
}
