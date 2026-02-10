// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

public class StepFailedException extends StepException {
    public StepFailedException(Operation operation) {
        super(
                operation,
                operation.stepDetails().error(),
                formatMessage(operation.stepDetails().error()));
    }

    private static String formatMessage(ErrorObject errorObject) {
        if (errorObject == null) {
            return "Step failed without an error";
        }
        return String.format(
                "Step failed with error of type %s. Message: %s", errorObject.errorType(), errorObject.errorMessage());
    }
}
