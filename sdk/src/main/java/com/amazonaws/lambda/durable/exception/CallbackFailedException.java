// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

/** Exception thrown when a callback fails due to an error from the external system. */
public class CallbackFailedException extends DurableExecutionException {
    public CallbackFailedException(ErrorObject error) {
        super(error.errorType() + ": " + error.errorMessage());
    }

    public CallbackFailedException(String message) {
        super(message);
    }
}
