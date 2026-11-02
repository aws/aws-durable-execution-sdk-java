// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

class CallbackExceptionTest {
    String CALLBACK_ID = "123";
    ErrorObject ERROR_OBJECT = ErrorObject.builder()
            .errorType("MyErrorType")
            .errorMessage("MyErrorMessage")
            .build();
    Operation OPERATION = Operation.builder()
            .callbackDetails(CallbackDetails.builder()
                    .callbackId(CALLBACK_ID)
                    .error(ERROR_OBJECT)
                    .build())
            .build();

    @Test
    void testCallbackTimeoutException() {
        var exception = new CallbackTimeoutException(OPERATION);
        assertEquals(CALLBACK_ID, exception.getCallbackId());
        assertEquals(OPERATION, exception.getOperation());
        assertEquals("Callback timed out: " + CALLBACK_ID, exception.getMessage());
    }

    @Test
    void testCallbackFailedException() {
        var exception = new CallbackFailedException(OPERATION);
        assertEquals(CALLBACK_ID, exception.getCallbackId());
        assertEquals(OPERATION, exception.getOperation());
        assertEquals(ERROR_OBJECT, exception.getErrorObject());
        assertEquals("MyErrorType: MyErrorMessage", exception.getMessage());
    }

    @Test
    void testCallbackFailedExceptionWithoutError() {
        var operation = Operation.builder()
                .callbackDetails(
                        CallbackDetails.builder().callbackId(CALLBACK_ID).build())
                .build();

        var exception = new CallbackFailedException(operation);
        assertEquals(CALLBACK_ID, exception.getCallbackId());
        assertEquals(operation, exception.getOperation());
        assertNull(exception.getErrorObject());
        assertEquals("Callback failed", exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "RejectedError,NULL,RejectedError",
                "RejectedError,'',RejectedError",
                "RejectedError,denied,RejectedError: denied",
                "NULL,denied,denied",
                "'',denied,denied",
                "NULL,NULL,Callback failed",
                "NULL,'',Callback failed",
                "'',NULL,Callback failed",
                "'','',Callback failed"
            },
            nullValues = "NULL")
    void testCallbackFailureMessagePreservesPresentFields(String type, String message, String expected) {
        var error = ErrorObject.builder().errorType(type).errorMessage(message).build();
        var operation = Operation.builder()
                .callbackDetails(CallbackDetails.builder()
                        .callbackId(CALLBACK_ID)
                        .error(error)
                        .build())
                .build();

        var exception = new CallbackFailedException(operation);
        assertEquals(expected, exception.getMessage());
        assertEquals(error, exception.getErrorObject());
        assertEquals(type, exception.getErrorObject().errorType());
        assertEquals(message, exception.getErrorObject().errorMessage());
    }

    @Test
    void testCallbackException() {
        var exception = new CallbackException(OPERATION, "myErrorMessage");
        assertEquals(CALLBACK_ID, exception.getCallbackId());
        assertEquals(OPERATION, exception.getOperation());
        assertEquals(ERROR_OBJECT, exception.getErrorObject());
        assertEquals("myErrorMessage", exception.getMessage());
    }
}
