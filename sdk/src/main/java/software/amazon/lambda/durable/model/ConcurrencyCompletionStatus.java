// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

public enum ConcurrencyCompletionStatus {
    ALL_COMPLETED,
    MIN_SUCCESSFUL_REACHED,
    MIN_SUCCESSFUL_NOT_REACHED,
    FAILURE_TOLERANCE_EXCEEDED;

    @Override
    public String toString() {
        return name();
    }

    public boolean isSucceeded() {
        return this == ALL_COMPLETED || this == MIN_SUCCESSFUL_REACHED;
    }
}
