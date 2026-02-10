// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.StepDetails;

class StepFailedExceptionTest {
    ErrorObject ERROR_OBJECT = ErrorObject.builder()
            .errorType("MyErrorType")
            .errorMessage("MyErrorMessage")
            .build();

    @Test
    void testConstructorWithNullErrorObject() {
        var op = Operation.builder().stepDetails(StepDetails.builder().build()).build();
        var exception = new StepFailedException(op);
        assertEquals(op, exception.getOperation());
        assertNull(exception.getErrorObject());
        assertEquals("Step failed without an error", exception.getMessage());
    }

    @Test
    void testConstructorWithErrorObject() {
        var op = Operation.builder()
                .stepDetails(StepDetails.builder().error(ERROR_OBJECT).build())
                .build();
        var exception = new StepFailedException(op);

        assertEquals(op, exception.getOperation());
        assertEquals(ERROR_OBJECT, exception.getErrorObject());
        assertEquals("Step failed with error of type MyErrorType. Message: MyErrorMessage", exception.getMessage());
    }
}
