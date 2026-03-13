// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.util.ExceptionHelper;

/** Exception associated with a specific durable operation, carrying the operation and error details. */
public class DurableOperationException extends DurableExecutionException {
    private final Operation operation;
    private final ErrorObject errorObject;

    public DurableOperationException(Operation operation, ErrorObject errorObject) {
        this(operation, errorObject, errorObject != null ? errorObject.errorMessage() : null);
    }

    public DurableOperationException(Operation operation, ErrorObject errorObject, String errorMessage) {
        this(operation, errorObject, errorMessage, null);
    }

    public DurableOperationException(
            Operation operation, ErrorObject errorObject, String errorMessage, Throwable cause) {
        this(
                operation,
                errorObject,
                errorMessage,
                errorObject != null ? ExceptionHelper.deserializeStackTrace(errorObject.stackTrace()) : null,
                cause);
    }

    public DurableOperationException(
            Operation operation,
            ErrorObject errorObject,
            String errorMessage,
            StackTraceElement[] stackTrace,
            Throwable cause) {
        super(errorMessage, cause, stackTrace);
        this.operation = operation;
        this.errorObject = errorObject;
    }

    /** Returns the error details from the failed operation. */
    public ErrorObject getErrorObject() {
        return errorObject;
    }

    /** Returns the operation that caused this exception. */
    public Operation getOperation() {
        return operation;
    }

    /** Returns the status of the operation that caused this exception. */
    public OperationStatus getOperationStatus() {
        return operation.status();
    }

    /** Returns the ID of the operation that caused this exception. */
    public String getOperationId() {
        return operation.id();
    }
}
