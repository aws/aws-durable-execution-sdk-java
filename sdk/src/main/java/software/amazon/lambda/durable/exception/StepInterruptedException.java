// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

/** Exception thrown when a step with AT_MOST_ONCE_PER_RETRY semantics was started but interrupted before completion. */
public class StepInterruptedException extends StepException {
    private static final String ERROR_TYPE = StepInterruptedException.class.getName();

    public StepInterruptedException(Operation operation) {
        super(operation, toErrorObject(operation), formatMessage(operation));
    }

    public static boolean isStepInterruptedException(ErrorObject errorObject) {
        if (errorObject == null) {
            return false;
        }
        return ERROR_TYPE.equals(errorObject.errorType());
    }

    private static ErrorObject toErrorObject(Operation operation) {
        return ErrorObject.builder()
                .errorType(ERROR_TYPE)
                .errorMessage(formatMessage(operation))
                .build();
    }

    private static String formatMessage(Operation operation) {
        var message = String.format(
                "The step execution was initiated but failed to reach completion due to an interruption. Operation ID: %s",
                operation.id());
        if (operation.name() != null) {
            message += String.format(", Step Name: %s", operation.name());
        }
        return message;
    }
}
