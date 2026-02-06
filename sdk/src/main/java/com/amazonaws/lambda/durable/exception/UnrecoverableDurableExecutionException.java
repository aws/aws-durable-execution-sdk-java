// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

/** Exception thrown when the execution is not recoverable. The durable execution will be immediately terminated. */
public class UnrecoverableDurableExecutionException extends DurableExecutionException {
    private final ErrorObject errorObject;

    public UnrecoverableDurableExecutionException(ErrorObject errorObject) {
        super(errorObject.errorMessage());
        this.errorObject = errorObject;
    }

    public ErrorObject getErrorObject() {
        return errorObject;
    }
}
