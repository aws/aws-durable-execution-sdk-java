// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/**
 * Result returned by a WaitForCondition check function to signal whether the condition is met.
 *
 * <p>When {@code isDone} is true, polling stops and {@code value} becomes the final result. When {@code isDone} is
 * false, polling continues using the delay computed by the wait strategy.
 *
 * @param value the current state after evaluation
 * @param isDone true if the condition is met and polling should stop, false to continue polling
 * @param <T> the type of the state value
 */
public record WaitForConditionResult<T>(T value, boolean isDone) {

    /**
     * Creates a result indicating the condition is met and polling should stop.
     *
     * @param value the final state value
     * @param <T> the type of the state value
     * @return a WaitForConditionResult with isDone=true
     */
    public static <T> WaitForConditionResult<T> stopPolling(T value) {
        return new WaitForConditionResult<>(value, true);
    }

    /**
     * Creates a result indicating polling should continue with the given state.
     *
     * @param value the current state value to pass to the next check
     * @param <T> the type of the state value
     * @return a WaitForConditionResult with isDone=false
     */
    public static <T> WaitForConditionResult<T> continuePolling(T value) {
        return new WaitForConditionResult<>(value, false);
    }
}
