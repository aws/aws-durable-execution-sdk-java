// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;

public class InvokeException extends DurableExecutionException {
    private final ErrorObject errorObject;
    private final OperationStatus operationStatus;

    public InvokeException(OperationStatus operationStatus, ErrorObject errorObject) {
        super(
                errorObject != null ? errorObject.errorMessage() : null,
                null,
                errorObject != null ? DurableExecutionException.deserializeStackTrace(errorObject.stackTrace()) : null);
        this.operationStatus = operationStatus;
        this.errorObject = errorObject;
    }

    public String getErrorData() {
        return errorObject == null ? null : errorObject.errorData();
    }

    public String getErrorType() {
        return errorObject == null ? null : errorObject.errorType();
    }

    public OperationStatus getOperationStatus() {
        return operationStatus;
    }
}
