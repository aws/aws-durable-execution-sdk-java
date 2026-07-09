// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import java.util.Objects;
import java.util.function.Function;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;

/**
 * Controls when a concurrent operation (map or parallel) completes.
 *
 * <p>Provides factory methods for common completion strategies and fine-grained control via {@code minSuccessful},
 * {@code toleratedFailureCount}, {@code toleratedFailurePercentage}, and {@code shouldComplete}.
 */
public record CompletionConfig(
        Integer minSuccessful,
        Integer toleratedFailureCount,
        Double toleratedFailurePercentage,
        Function<CompletionStatus, CompletionDecision> shouldComplete) {

    public CompletionConfig(Integer minSuccessful, Integer toleratedFailureCount, Double toleratedFailurePercentage) {
        this(minSuccessful, toleratedFailureCount, toleratedFailurePercentage, null);
    }

    public CompletionConfig {
        if (shouldComplete != null
                && (minSuccessful != null || toleratedFailureCount != null || toleratedFailurePercentage != null)) {
            throw new IllegalArgumentException(
                    "shouldComplete is mutually exclusive with minSuccessful, toleratedFailureCount, and toleratedFailurePercentage");
        }
    }

    /**
     * Live completion progress for a map or parallel operation.
     *
     * @param successCount completed items that succeeded
     * @param failureCount completed items that failed
     * @param completedCount completed items, equal to successCount + failureCount
     * @param totalCount registered items
     * @param allItemsRegistered true once the caller has registered all items
     */
    public record CompletionStatus(
            int successCount, int failureCount, int completedCount, int totalCount, boolean allItemsRegistered) {
        public CompletionStatus(int successCount, int failureCount, int completedCount, int totalCount) {
            this(successCount, failureCount, completedCount, totalCount, completedCount == totalCount);
        }

        public CompletionStatus {
            if (successCount < 0 || failureCount < 0 || completedCount < 0 || totalCount < 0) {
                throw new IllegalArgumentException("completion counts must be non-negative");
            }
            if (completedCount != successCount + failureCount) {
                throw new IllegalArgumentException("completedCount must equal successCount + failureCount");
            }
            if (completedCount > totalCount) {
                throw new IllegalArgumentException("completedCount cannot exceed totalCount");
            }
        }

        public boolean allCompleted() {
            return allItemsRegistered && completedCount == totalCount;
        }
    }

    /**
     * The completion decision returned by {@link #completionDecisionFunction()}.
     *
     * @param shouldComplete true if the concurrent operation should complete now
     * @param completionStatus status to report when completing, or null when continuing
     */
    public record CompletionDecision(boolean shouldComplete, ConcurrencyCompletionStatus completionStatus) {
        public CompletionDecision {
            if (shouldComplete && completionStatus == null) {
                throw new IllegalArgumentException("completionStatus is required when shouldComplete is true");
            }
            if (!shouldComplete && completionStatus != null) {
                throw new IllegalArgumentException("completionStatus must be null when shouldComplete is false");
            }
        }

        public static CompletionDecision complete(ConcurrencyCompletionStatus completionStatus) {
            return new CompletionDecision(true, completionStatus);
        }

        public static CompletionDecision continueExecution() {
            return new CompletionDecision(false, null);
        }

        public boolean isSucceeded() {
            return shouldComplete && completionStatus.isSucceeded();
        }
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

    /**
     * Complete when the function returns a completing decision.
     *
     * <p>The decision controls whether the operation completes, the reported completion status, and whether that status
     * represents success.
     */
    public static CompletionConfig shouldComplete(Function<CompletionStatus, CompletionDecision> shouldComplete) {
        Objects.requireNonNull(shouldComplete, "shouldComplete cannot be null");
        return new CompletionConfig(null, null, null, shouldComplete);
    }

    /**
     * Returns the completion decision function. Legacy threshold fields are converted into this function so concurrent
     * operations only need one completion mechanism.
     */
    public Function<CompletionStatus, CompletionDecision> completionDecisionFunction() {
        return shouldComplete != null ? shouldComplete : thresholdBasedShouldComplete();
    }

    /** @return true when a custom completion function is configured. */
    public boolean hasCustomShouldComplete() {
        return shouldComplete != null;
    }

    private Function<CompletionStatus, CompletionDecision> thresholdBasedShouldComplete() {
        return status -> {
            if (minSuccessful != null) {
                if (status.successCount() >= minSuccessful) {
                    return CompletionDecision.complete(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED);
                }
                if (status.allItemsRegistered() && status.totalCount() < minSuccessful) {
                    throw new IllegalStateException("minSuccessful (" + minSuccessful
                            + ") exceeds the number of registered items (" + status.totalCount() + ")");
                }
            }

            var toleratedFailures = toleratedFailureLimit(status.totalCount());
            if (toleratedFailures != null && status.failureCount() > toleratedFailures) {
                return CompletionDecision.complete(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED);
            }

            if (status.allCompleted()) {
                return CompletionDecision.complete(ConcurrencyCompletionStatus.ALL_COMPLETED);
            }

            return CompletionDecision.continueExecution();
        };
    }

    private Integer toleratedFailureLimit(int totalCount) {
        if (toleratedFailureCount == null && toleratedFailurePercentage == null) {
            return null;
        }
        var count = toleratedFailureCount != null ? toleratedFailureCount : Integer.MAX_VALUE;
        var percentageCount = toleratedFailurePercentage != null
                ? (int) Math.floor(totalCount * toleratedFailurePercentage)
                : Integer.MAX_VALUE;
        return Math.min(count, percentageCount);
    }
}
