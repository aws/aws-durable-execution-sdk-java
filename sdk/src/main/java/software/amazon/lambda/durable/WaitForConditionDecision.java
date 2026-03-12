// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.time.Duration;

/**
 * Represents the decision made by a {@link WaitForConditionWaitStrategy} after evaluating the current state.
 *
 * <p>If {@code shouldContinue} is true, {@code delay} specifies how long to wait before the next poll. If
 * {@code shouldContinue} is false, polling stops and the current state becomes the result.
 *
 * @param shouldContinue true if polling should continue, false if the condition has been met
 * @param delay the duration to wait before the next poll (null when shouldContinue is false)
 */
public record WaitForConditionDecision(boolean shouldContinue, Duration delay) {

    /**
     * Creates a decision to continue polling after the specified delay.
     *
     * @param delay the duration to wait before the next poll, must be at least 1 second
     * @return a continue-polling decision
     */
    public static WaitForConditionDecision continuePolling(Duration delay) {
        if (delay == null) {
            throw new IllegalArgumentException("delay cannot be null");
        }
        return new WaitForConditionDecision(true, delay);
    }

    /**
     * Creates a decision to stop polling.
     *
     * @return a stop-polling decision
     */
    public static WaitForConditionDecision stopPolling() {
        return new WaitForConditionDecision(false, null);
    }
}
