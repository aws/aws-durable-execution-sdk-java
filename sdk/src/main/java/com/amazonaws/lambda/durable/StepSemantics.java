// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

/**
 * Delivery semantics for step operations.
 *
 * <p>Controls how the SDK handles step execution and interruption recovery.
 */
public enum StepSemantics {
    /**
     * At-least-once delivery (default). The step may be re-executed if interrupted.
     * START checkpoint is fire-and-forget.
     */
    AT_LEAST_ONCE_PER_RETRY,

    /**
     * At-most-once delivery per retry attempt. The step will not be re-executed if interrupted.
     * START checkpoint is awaited before user code runs.
     * If interrupted, throws {@link com.amazonaws.lambda.durable.exception.StepInterruptedException}.
     */
    AT_MOST_ONCE_PER_RETRY
}
