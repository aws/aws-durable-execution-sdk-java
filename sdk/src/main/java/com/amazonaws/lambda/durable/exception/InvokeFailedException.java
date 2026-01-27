// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import java.util.List;

public class InvokeFailedException extends DurableExecutionException {
    String errorData;
    String errorType;

    public InvokeFailedException(String errorData, String errorMessage, String errorType, List<String> stackTrace) {
        super(errorMessage, null, DurableExecutionException.deserializeStackTrace(stackTrace));
        this.errorType = errorType;
        this.errorData = errorData;
    }

    public InvokeFailedException() {
        super(null, null);
    }

    public String getErrorData() {
        return errorData;
    }

    public String getErrorType() {
        return errorType;
    }
}
