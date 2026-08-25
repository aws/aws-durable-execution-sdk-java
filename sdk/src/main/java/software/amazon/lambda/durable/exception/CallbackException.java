// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.util.ExceptionHelper;

/** Thrown when a callback operation encounters an error. */
public class CallbackException extends DurableOperationException {
    private final String callbackId;

    public CallbackException(Operation operation, String message) {
        this(operation, message, null);
    }

    public CallbackException(Operation operation, String message, Throwable cause) {
        this(operation, message, cause, null);
    }

    protected CallbackException(Operation operation, String message, Throwable cause, Throwable deserializedError) {
        super(
                operation,
                operation.callbackDetails().error(),
                message,
                operation.callbackDetails().error() != null
                        ? ExceptionHelper.deserializeStackTrace(
                                operation.callbackDetails().error().stackTrace())
                        : null,
                cause,
                deserializedError);
        this.callbackId = operation.callbackDetails().callbackId();
    }

    /** Returns the callback ID associated with this exception. */
    public String getCallbackId() {
        return callbackId;
    }
}
