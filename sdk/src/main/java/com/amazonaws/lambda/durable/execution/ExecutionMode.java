// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

/** Represents the current execution mode of a durable execution. */
enum ExecutionMode {
    /** Replaying completed operations from checkpoint log. */
    REPLAY,
    /** Executing new operations. */
    EXECUTION
}
