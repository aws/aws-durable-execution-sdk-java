// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

/** Thrown when a map iteration fails and deserialization of the original exception also fails. */
public class MapIterationFailedException extends DurableOperationException {
    public MapIterationFailedException(Operation operation) {
        super(operation, getError(operation), formatMessage(getError(operation)));
    }

    private static ErrorObject getError(Operation operation) {
        return operation.contextDetails() != null ? operation.contextDetails().error() : null;
    }

    private static String formatMessage(ErrorObject errorObject) {
        if (errorObject == null) {
            return "Map iteration failed without an error";
        }
        return String.format(
                "Map iteration failed with error of type %s. Message: %s",
                errorObject.errorType(), errorObject.errorMessage());
    }
}
