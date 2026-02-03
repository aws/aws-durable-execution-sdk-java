// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

public class StepFailedException extends StepException {
    public StepFailedException(Operation operation, ErrorObject errorObject) {
        super(operation, errorObject, formatMessage(errorObject));
    }

    private static String formatMessage(ErrorObject errorObject) {
        if (errorObject == null) {
            return "Step failed with null error";
        }
        return String.format(
                "Step failed with error of type %s. Message: %s", errorObject.errorType(), errorObject.errorMessage());
    }
}
