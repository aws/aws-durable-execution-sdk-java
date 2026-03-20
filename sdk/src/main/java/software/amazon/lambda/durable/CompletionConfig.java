// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/**
 * Controls when a concurrent operation (map or parallel) completes.
 *
 * <p>Provides factory methods for common completion strategies and fine-grained control via {@code minSuccessful},
 * {@code toleratedFailureCount}, and {@code toleratedFailurePercentage}.
 */
public class CompletionConfig {
    private final Integer minSuccessful;
    private final Integer toleratedFailureCount;
    private final Double toleratedFailurePercentage;

    private CompletionConfig(Integer minSuccessful, Integer toleratedFailureCount, Double toleratedFailurePercentage) {
        this.minSuccessful = minSuccessful;
        this.toleratedFailureCount = toleratedFailureCount;
        this.toleratedFailurePercentage = toleratedFailurePercentage;
    }

    /** All items must succeed. Zero failures tolerated. */
    public static CompletionConfig allSuccessful() {
        return new CompletionConfig(null, 0, null);
    }

    /** All items run regardless of failures. Failures captured per-item. */
    public static CompletionConfig allCompleted() {
        return new CompletionConfig(null, null, null);
    }

    /** Complete as soon as the first item succeeds. */
    public static CompletionConfig firstSuccessful() {
        return new CompletionConfig(1, null, null);
    }

    /** Complete when the specified number of items have succeeded. */
    public static CompletionConfig minSuccessful(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("minSuccessful must be at least 1, got: " + count);
        }
        return new CompletionConfig(count, null, null);
    }

    /** Complete when more than the specified number of failures have occurred. */
    public static CompletionConfig toleratedFailureCount(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("toleratedFailureCount must be non-negative, got: " + count);
        }
        return new CompletionConfig(null, count, null);
    }

    /** Complete when the failure percentage exceeds the specified threshold (0.0 to 1.0). */
    public static CompletionConfig toleratedFailurePercentage(double percentage) {
        if (percentage < 0.0 || percentage > 1.0) {
            throw new IllegalArgumentException(
                    "toleratedFailurePercentage must be between 0.0 and 1.0, got: " + percentage);
        }
        return new CompletionConfig(null, null, percentage);
    }

    /** @return minimum number of successful items required, or null if not set */
    public Integer minSuccessful() {
        return minSuccessful;
    }

    /** @return maximum number of failures tolerated, or null if unlimited */
    public Integer toleratedFailureCount() {
        return toleratedFailureCount;
    }

    /** @return maximum percentage of failures tolerated (0.0 to 1.0), or null if not set */
    public Double toleratedFailurePercentage() {
        return toleratedFailurePercentage;
    }
}
