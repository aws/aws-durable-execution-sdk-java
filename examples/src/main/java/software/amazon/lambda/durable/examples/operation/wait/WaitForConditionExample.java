// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.wait;

import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation.WaitForConditionConfig;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation.WaitForConditionResult;

/**
 * Example demonstrating the waitForCondition operation.
 *
 * <p>This handler polls a condition function until it signals completion:
 *
 * <ol>
 *   <li>The attempt count is used as a state (replay safe)
 *   <li>Fails and retries until the attempt count reaches the given threshold, and then succeeds
 * </ol>
 */
public class WaitForConditionExample extends DurableHandler<Integer, Integer> {

    @Override
    public Integer handleRequest(Integer threshold) {
        // Poll until the counter reaches the input threshold
        return DurableWaitForConditionOperation.waitForCondition(
                "wait-for-condition",
                Integer.class,
                callCount -> {
                    if (callCount >= threshold) {
                        // Condition met, stop polling
                        return WaitForConditionResult.stopPolling(callCount);
                    }
                    // Condition not met, keep polling
                    return WaitForConditionResult.continuePolling(callCount + 1);
                },
                WaitForConditionConfig.<Integer>builder().initialState(1).build());
    }
}
