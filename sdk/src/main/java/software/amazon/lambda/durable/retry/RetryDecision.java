// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import java.time.Duration;

/** Represents a decision about whether to retry a failed operation and how long to wait. */
public class RetryDecision {
    private final boolean shouldRetry;
    private final Duration delay;

    private RetryDecision(boolean shouldRetry, Duration delay) {
        this.shouldRetry = shouldRetry;
        this.delay = delay != null ? delay : Duration.ZERO;
    }

    /**
     * Creates a retry decision indicating the operation should be retried after the specified delay.
     *
     * @param delay the duration to wait before retrying
     * @return a RetryDecision indicating retry with the specified delay
     */
    public static RetryDecision retry(Duration delay) {
        return new RetryDecision(true, delay);
    }

    /**
     * Creates a retry decision indicating the operation should not be retried.
     *
     * @return a RetryDecision indicating no retry should be attempted
     */
    public static RetryDecision fail() {
        return new RetryDecision(false, Duration.ZERO);
    }

    /** @return true if the operation should be retried, false otherwise */
    public boolean shouldRetry() {
        return shouldRetry;
    }

    /** @return the duration to wait before retrying, or Duration.ZERO if no retry */
    public Duration delay() {
        return delay;
    }

    @Override
    public String toString() {
        return shouldRetry ? String.format("RetryDecision{retry after %s}", delay) : "RetryDecision{fail}";
    }
}
