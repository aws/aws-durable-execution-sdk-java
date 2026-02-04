// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;

public class CallbackException extends DurableOperationException {
    private final String callbackId;

    public CallbackException(Operation operation, String message) {
        super(operation, operation.callbackDetails().error(), message);
        this.callbackId = operation.callbackDetails().callbackId();
    }

    public String getCallbackId() {
        return callbackId;
    }
}
