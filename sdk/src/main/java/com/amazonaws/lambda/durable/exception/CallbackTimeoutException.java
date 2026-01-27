// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

/** Exception thrown when a callback times out. */
public class CallbackTimeoutException extends DurableExecutionException {
    public CallbackTimeoutException(String callbackId) {
        super("Callback timed out: " + callbackId);
    }
}
