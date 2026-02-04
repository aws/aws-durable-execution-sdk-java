// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;

/** Exception thrown when a callback times out. */
public class CallbackTimeoutException extends CallbackException {
    public CallbackTimeoutException(String callbackId, Operation operation) {
        super(operation, "Callback timed out: " + callbackId);
    }
}
