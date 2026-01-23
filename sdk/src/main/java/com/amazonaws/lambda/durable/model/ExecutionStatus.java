// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.model;

public enum ExecutionStatus {
    SUCCEEDED,
    FAILED,
    PENDING;

    @Override
    public String toString() {
        return name();
    }
}
