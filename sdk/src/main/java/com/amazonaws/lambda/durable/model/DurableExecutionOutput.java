// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

public record DurableExecutionOutput(ExecutionStatus status, String result, ErrorObject error) {
    public static DurableExecutionOutput success(String result) {
        return new DurableExecutionOutput(ExecutionStatus.SUCCEEDED, result, null);
    }

    public static DurableExecutionOutput pending() {
        return new DurableExecutionOutput(ExecutionStatus.PENDING, null, null);
    }

    public static DurableExecutionOutput failure(ErrorObject errorObject) {
        return new DurableExecutionOutput(ExecutionStatus.FAILED, null, errorObject);
    }
}
