// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

/** Exception thrown when a callback fails due to an error from the external system. */
public class CallbackFailedException extends DurableExecutionException {
    public CallbackFailedException(ErrorObject error) {
        super(
                buildMessage(error),
                null,
                error.stackTrace() != null && !error.stackTrace().isEmpty()
                        ? deserializeStackTrace(error.stackTrace())
                        : new StackTraceElement[0]);
    }

    public CallbackFailedException(String message) {
        super(message);
    }

    private static String buildMessage(ErrorObject error) {
        var errorType = error.errorType();
        var errorMessage = error.errorMessage();

        if (errorType != null && !errorType.isEmpty()) {
            return errorType + ": " + errorMessage;
        }
        return errorMessage;
    }
}
