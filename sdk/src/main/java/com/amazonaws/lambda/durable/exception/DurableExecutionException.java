// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

public class DurableExecutionException extends RuntimeException {
    public DurableExecutionException(String message, Throwable cause, StackTraceElement[] stackTrace) {
        super(message, cause);
        if (stackTrace != null) {
            this.setStackTrace(stackTrace);
        }
    }

    public DurableExecutionException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public DurableExecutionException(String message) {
        this(message, null, null);
    }
}
