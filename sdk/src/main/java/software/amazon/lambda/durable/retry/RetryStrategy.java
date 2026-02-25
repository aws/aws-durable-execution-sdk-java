// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

/**
 * Functional interface for determining retry behavior when operations fail.
 *
 * <p>A RetryStrategy evaluates failed operations and decides whether they should be retried and how long to wait before
 * the next attempt.
 */
@FunctionalInterface
public interface RetryStrategy {

    /**
     * Determines whether to retry a failed operation and calculates the retry delay.
     *
     * @param error The error that occurred during the operation
     * @param attemptNumber The current attempt number (0-based, so first attempt is 0)
     * @return RetryDecision indicating whether to retry and the delay before next attempt
     */
    RetryDecision makeRetryDecision(Throwable error, int attemptNumber);
}
