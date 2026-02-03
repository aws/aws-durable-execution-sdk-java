// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

/** Exception thrown when a callback fails due to an error from the external system. */
public class CallbackFailedException extends CallbackException {
    public CallbackFailedException(String callbackId, Operation operation, ErrorObject error) {
        super(callbackId, operation, error, buildMessage(error));
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
