// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

/**
 * Delivery semantics for step operations.
 *
 * <p>Controls how the SDK handles step execution and interruption recovery.
 */
public enum StepSemantics {
    /**
     * At-least-once delivery (default). The step may be re-executed if interrupted. START checkpoint is
     * fire-and-forget.
     */
    AT_LEAST_ONCE_PER_RETRY,

    /**
     * At-most-once delivery per retry attempt. START checkpoint is awaited before user code runs. If interrupted,
     * throws {@link software.amazon.lambda.durable.exception.StepInterruptedException}.
     *
     * <p>When used with {@link StepConfig.Builder#semantics(StepSemantics)}: interrupted steps are NOT retried
     * (incorrect behavior, kept for backward compatibility).
     *
     * <p>When used with {@link StepConfig.Builder#semanticsPerRetry(StepSemantics)}: interrupted steps are retried
     * according to the retry strategy (correct behavior).
     */
    AT_MOST_ONCE_PER_RETRY
}
