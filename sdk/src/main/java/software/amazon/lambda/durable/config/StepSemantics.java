// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

/**
 * Delivery semantics for step operations.
 *
 * <p>Controls how the SDK handles step execution and interruption recovery. The guarantees apply per retry attempt, not
 * per execution.
 */
public enum StepSemantics {
    /**
     * At-least-once delivery per retry attempt (default). The step may be re-executed if interrupted. START checkpoint
     * is fire-and-forget.
     */
    AT_LEAST_ONCE,

    /**
     * At-most-once delivery per retry attempt. START checkpoint is awaited before user code runs. If interrupted,
     * throws {@link software.amazon.lambda.durable.exception.StepInterruptedException}.
     */
    AT_MOST_ONCE,

    /**
     * At-least-once delivery per retry attempt, without waiting for the result checkpoint.
     *
     * <p>Works like {@link #AT_LEAST_ONCE}, with one change. The step returns its result as soon as the SUCCEED
     * checkpoint is queued. It does not wait for the service to save it. So the step has lower latency.
     *
     * <p>The code after the step can run before the result is saved. Checkpoints are sent in order, and the invocation
     * does not end or suspend before the service answers the SUCCEED checkpoint. If that checkpoint fails, the
     * invocation fails, and the step runs again on replay.
     *
     * <p>On that replay the step can return a different value. Later steps then run again with the new value, and their
     * earlier side effects used the old one. So do not use this mode for a step whose result becomes an idempotency key
     * or a client token for a later step.
     *
     * <p>Only the SUCCEED checkpoint is not awaited. A failed attempt waits for its RETRY or FAIL checkpoint.
     */
    AT_LEAST_ONCE_ASYNC,

    /**
     * At-least-once delivery per retry attempt.
     *
     * @deprecated Use {@link #AT_LEAST_ONCE} instead. It has the same behavior.
     */
    @Deprecated
    AT_LEAST_ONCE_PER_RETRY,

    /**
     * At-most-once delivery per retry attempt.
     *
     * @deprecated Use {@link #AT_MOST_ONCE} instead. It has the same behavior.
     */
    @Deprecated
    AT_MOST_ONCE_PER_RETRY
}
