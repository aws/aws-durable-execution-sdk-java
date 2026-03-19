// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import java.time.Duration;
import software.amazon.lambda.durable.exception.WaitForConditionException;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/**
 * Strategy that computes the delay before the next polling attempt in a {@code waitForCondition} operation.
 *
 * <p>Implementations evaluate the current state and attempt number to compute a {@link Duration} delay. The
 * continue/stop decision is handled separately by {@link WaitForConditionResult}. When the maximum number of attempts
 * is exceeded, the strategy should throw a {@link WaitForConditionException}.
 *
 * @param <T> the type of state being polled
 * @see WaitStrategies
 */
@FunctionalInterface
public interface WaitForConditionWaitStrategy<T> {

    /**
     * Computes the delay before the next polling attempt based on the current state and attempt number.
     *
     * @param state the current state returned by the check function
     * @param attempt the attempt number
     * @return a {@link Duration} representing the delay before the next polling attempt
     * @throws WaitForConditionException if the maximum number of attempts has been exceeded
     */
    Duration evaluate(T state, int attempt);
}
