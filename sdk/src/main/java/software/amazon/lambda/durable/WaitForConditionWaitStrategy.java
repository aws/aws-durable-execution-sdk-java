// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/**
 * Strategy that determines whether to continue or stop polling in a {@code waitForCondition} operation.
 *
 * <p>Implementations evaluate the current state and attempt number to decide whether to continue polling (with a
 * specified delay) or stop polling because the condition has been met.
 *
 * @param <T> the type of state being polled
 * @see WaitForConditionDecision
 * @see WaitStrategies
 */
@FunctionalInterface
public interface WaitForConditionWaitStrategy<T> {

    /**
     * Evaluates the current state and attempt number to decide whether to continue or stop polling.
     *
     * @param state the current state returned by the check function
     * @param attempt the attempt number
     * @return a {@link WaitForConditionDecision} indicating whether to continue or stop polling
     */
    WaitForConditionDecision evaluate(T state, int attempt);
}
