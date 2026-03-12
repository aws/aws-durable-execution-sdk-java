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
