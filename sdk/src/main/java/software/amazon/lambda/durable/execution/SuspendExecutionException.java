// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

/** Exception thrown to suspend execution during wait operations. This is an internal control flow mechanism. */
public class SuspendExecutionException extends RuntimeException {
    public SuspendExecutionException() {
        super("Execution suspended for wait operation");
    }
}
