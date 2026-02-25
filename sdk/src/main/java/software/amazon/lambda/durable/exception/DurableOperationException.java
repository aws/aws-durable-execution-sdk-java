// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.util.ExceptionHelper;

public class DurableOperationException extends DurableExecutionException {
    private final Operation operation;
    private final ErrorObject errorObject;

    public DurableOperationException(Operation operation, ErrorObject errorObject) {
        this(operation, errorObject, errorObject != null ? errorObject.errorMessage() : null);
    }

    public DurableOperationException(Operation operation, ErrorObject errorObject, String errorMessage) {
        this(
                operation,
                errorObject,
                errorMessage,
                errorObject != null ? ExceptionHelper.deserializeStackTrace(errorObject.stackTrace()) : null);
    }

    public DurableOperationException(
            Operation operation, ErrorObject errorObject, String errorMessage, StackTraceElement[] stackTrace) {
        super(errorMessage, null, stackTrace);
        this.operation = operation;
        this.errorObject = errorObject;
    }

    public ErrorObject getErrorObject() {
        return errorObject;
    }

    public Operation getOperation() {
        return operation;
    }

    public OperationStatus getOperationStatus() {
        return operation.status();
    }

    public String getOperationId() {
        return operation.id();
    }
}
