// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

public class StepFailedException extends DurableExecutionException {
    public StepFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public StepFailedException(String message, Throwable cause, StackTraceElement[] stackTrace) {
        super(message, cause, stackTrace);
    }
}
