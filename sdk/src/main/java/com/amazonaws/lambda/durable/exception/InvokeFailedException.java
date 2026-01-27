// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;

public class InvokeFailedException extends DurableExecutionException {
    private final ErrorObject errorObject;
    private final OperationStatus operationStatus;

    private InvokeFailedException(OperationStatus operationStatus, ErrorObject errorObject) {
        super(
                errorObject.errorMessage(),
                null,
                DurableExecutionException.deserializeStackTrace(errorObject.stackTrace()));
        this.operationStatus = operationStatus;
        this.errorObject = errorObject;
    }

    private InvokeFailedException(OperationStatus operationStatus) {
        super(null, null);
        this.operationStatus = operationStatus;
        errorObject = null;
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

    public static InvokeFailedException create(OperationStatus operationStatus, ErrorObject errorObject) {
        if (errorObject == null) {
            return new InvokeFailedException(operationStatus);
        } else {
            return new InvokeFailedException(operationStatus, errorObject);
        }
    }
}
