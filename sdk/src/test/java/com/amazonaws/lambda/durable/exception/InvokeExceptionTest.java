// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

class InvokeExceptionTest {

    @Test
    void testNullError() {
        var op = Operation.builder()
                .chainedInvokeDetails(ChainedInvokeDetails.builder().build())
                .type(OperationType.CHAINED_INVOKE)
                .id("10")
                .build();
        var exception = new InvokeFailedException(op);

        assertNull(exception.getErrorObject());
        assertNull(exception.getMessage());
    }

    @Test
    void testConstructorWithDefaultErrorObject() {
        var errorObject = ErrorObject.builder().build();
        var op = Operation.builder()
                .chainedInvokeDetails(
                        ChainedInvokeDetails.builder().error(errorObject).build())
                .type(OperationType.CHAINED_INVOKE)
                .id("10")
                .build();
        var exception = new InvokeTimedOutException(op);

        assertEquals(errorObject, exception.getErrorObject());
        assertNull(exception.getMessage());
    }

    @Test
    void testConstructorWithErrorObject() {
        var errorObject = ErrorObject.builder()
                .errorMessage("error message")
                .errorType("error type")
                .errorData("error data")
                .stackTrace(List.of("class1|method1|file1|10", "class2|method2|file2|20"))
                .build();
        var op = Operation.builder()
                .chainedInvokeDetails(
                        ChainedInvokeDetails.builder().error(errorObject).build())
                .type(OperationType.CHAINED_INVOKE)
                .id("10")
                .status(OperationStatus.FAILED)
                .build();
        var exception = new InvokeFailedException(op);

        assertEquals("error type", exception.getErrorObject().errorType());
        assertEquals("error data", exception.getErrorObject().errorData());
        assertEquals("error message", exception.getMessage());
        assertEquals(OperationStatus.FAILED, exception.getOperationStatus());
        assertEquals("10", exception.getOperationId());
        assertEquals(2, exception.getStackTrace().length);
        assertEquals(
                new StackTraceElement("class1", "method1", "file1", 10),
                exception.getStackTrace()[0]);
        assertEquals(
                new StackTraceElement("class2", "method2", "file2", 20),
                exception.getStackTrace()[1]);
    }
}
