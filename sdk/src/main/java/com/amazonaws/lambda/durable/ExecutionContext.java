// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

/**
 * Provides metadata about the current durable execution.
 *
 * <p>This context contains information about the execution environment that remains constant throughout the execution
 * lifecycle. Access it via {@link DurableContext#getExecutionContext()}.
 */
public class ExecutionContext {
    private final String durableExecutionArn;

    ExecutionContext(String durableExecutionArn) {
        this.durableExecutionArn = durableExecutionArn;
    }

    /**
     * Returns the ARN of the current durable execution.
     *
     * <p>The durable execution ARN uniquely identifies this execution instance and remains constant across all
     * invocations and replays. Use this ARN to:
     *
     * <ul>
     *   <li>Track execution progress in external systems
     *   <li>Correlate logs and metrics across invocations
     *   <li>Reference this execution when calling Lambda APIs
     * </ul>
     *
     * <p>Example ARN format:
     * {@code arn:aws:lambda:us-east-1:123456789012:function:my-function:$LATEST/durable-execution/349beff4-a89d-4bc8-a56f-af7a8af67a5f/20dae574-53da-37a1-bfd5-b0e2e6ec715d}
     *
     * @return the durable execution ARN
     */
    public String getDurableExecutionArn() {
        return durableExecutionArn;
    }
}
