// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

class StepFailedExceptionTest {
    Operation OPERATION = Operation.builder().build();
    ErrorObject ERROR_OBJECT = ErrorObject.builder()
            .errorType("MyErrorType")
            .errorMessage("MyErrorMessage")
            .build();

    @Test
    void testConstructorWithNullErrorObject() {
        var exception = new StepFailedException(OPERATION);
        assertEquals(OPERATION, exception.getOperation());
        assertNull(exception.getErrorObject());
        assertEquals("Step failed with null error", exception.getMessage());
    }

    @Test
    void testConstructorWithErrorObject() {
        var exception = new StepFailedException(OPERATION);

        assertEquals(OPERATION, exception.getOperation());
        assertEquals(ERROR_OBJECT, exception.getErrorObject());
        assertEquals("Step failed with error of type MyErrorType. Message: MyErrorMessage", exception.getMessage());
    }
}
