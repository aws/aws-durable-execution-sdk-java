// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

/** Exception thrown when a child context fails and the original exception cannot be reconstructed. */
public class ChildContextFailedException extends DurableOperationException {
    public ChildContextFailedException(Operation operation) {
        super(operation, getError(operation), formatMessage(getError(operation)));
    }

    private static ErrorObject getError(Operation operation) {
        return operation.contextDetails() != null ? operation.contextDetails().error() : null;
    }

    private static String formatMessage(ErrorObject errorObject) {
        if (errorObject == null) {
            return "Child context failed without an error";
        }
        return String.format(
                "Child context failed with error of type %s. Message: %s",
                errorObject.errorType(), errorObject.errorMessage());
    }
}
