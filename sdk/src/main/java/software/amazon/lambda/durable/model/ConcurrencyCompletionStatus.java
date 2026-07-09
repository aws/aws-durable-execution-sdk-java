// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

public enum ConcurrencyCompletionStatus {
    ALL_COMPLETED(true),
    MIN_SUCCESSFUL_REACHED(true),
    FAILURE_TOLERANCE_EXCEEDED(false),
    CUSTOM_COMPLETION_SUCCEEDED(true),
    CUSTOM_COMPLETION_FAILED(false);

    private final boolean succeeded;

    ConcurrencyCompletionStatus(boolean succeeded) {
        this.succeeded = succeeded;
    }

    @Override
    public String toString() {
        return name();
    }

    public boolean isSucceeded() {
        return succeeded;
    }
}
