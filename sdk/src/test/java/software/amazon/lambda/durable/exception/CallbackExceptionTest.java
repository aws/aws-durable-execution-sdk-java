// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
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
    void testCallbackException() {
        var exception = new CallbackException(OPERATION, "myErrorMessage");
        assertEquals(CALLBACK_ID, exception.getCallbackId());
        assertEquals(OPERATION, exception.getOperation());
        assertEquals(ERROR_OBJECT, exception.getErrorObject());
        assertEquals("myErrorMessage", exception.getMessage());
    }

    @Test
    void testCallbackFailedExceptionOmitsForeignStackTraceFrames() {
        var errorObject = ErrorObject.builder()
                .errorType("ExternalError")
                .errorMessage("external failure")
                .stackTrace(List.of("Traceback (most recent call last):", "  File \"handler.py\", line 12"))
                .build();
        var operation = Operation.builder()
                .callbackDetails(CallbackDetails.builder()
                        .callbackId(CALLBACK_ID)
                        .error(errorObject)
                        .build())
                .build();

        var exception = new CallbackFailedException(operation);

        assertEquals(errorObject, exception.getErrorObject());
        assertEquals("ExternalError: external failure", exception.getMessage());
        assertEquals(0, exception.getStackTrace().length);
    }
}
