// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

/**
 * Exception thrown when non-deterministic code is detected during replay. This indicates that the workflow code has
 * changed in a way that violates determinism requirements between the original execution and replay.
 */
public class NonDeterministicExecutionException extends UnrecoverableDurableExecutionException {
    public NonDeterministicExecutionException(String message) {
        super(ErrorObject.builder()
                .errorMessage(message)
                .errorType(NonDeterministicExecutionException.class.getName())
                .build());
    }
}
