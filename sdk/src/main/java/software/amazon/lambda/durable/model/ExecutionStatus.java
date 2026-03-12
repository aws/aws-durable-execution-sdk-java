// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/** Terminal status of a durable execution. */
public enum ExecutionStatus {
    SUCCEEDED,
    FAILED,
    PENDING;

    @Override
    public String toString() {
        return name();
    }
}
