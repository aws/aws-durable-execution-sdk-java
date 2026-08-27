// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/** Outcome of a user-provided function when its execution ends. */
public enum UserFunctionOutcome {
    /** The user function returned normally. */
    SUCCEEDED,
    /** The user function threw an error. */
    FAILED,
    /** The user function exited before completing, for example when the durable execution suspended. */
    INCOMPLETE
}
