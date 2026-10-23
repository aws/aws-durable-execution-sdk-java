// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;

class UnrecoverableDurableExecutionExceptionTest {

    @Test
    void testNonDeterministicExecutionException() {
        var exception = new NonDeterministicExecutionException("Non-deterministic behavior detected");

        assertEquals("Non-deterministic behavior detected", exception.getMessage());
        assertEquals(
                "Non-deterministic behavior detected",
                exception.getErrorObject().errorMessage());
        assertEquals(
                "software.amazon.lambda.durable.exception.NonDeterministicExecutionException",
                exception.getErrorObject().errorType());
        assertNull(exception.getCause());
        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(DurableExecutionException.class, exception);
    }

    @Test
    void testIllegalDurableOperationException() {
        var exception = new IllegalDurableOperationException("Illegal operation detected");
        assertEquals("Illegal operation detected", exception.getMessage());
        assertEquals("Illegal operation detected", exception.getErrorObject().errorMessage());
        assertEquals(
                "software.amazon.lambda.durable.exception.IllegalDurableOperationException",
                exception.getErrorObject().errorType());
        assertNull(exception.getCause());
        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(DurableExecutionException.class, exception);
    }

    @Test
    void preservesCauseForRetryableInvocationFailures() {
        var cause = new InterruptedException("interrupted");
        var error = ErrorObject.builder().errorMessage("cleanup failed").build();

        var exception = new UnrecoverableDurableExecutionException(error, true, cause);

        assertSame(cause, exception.getCause());
        assertTrue(exception.isRetryable());
    }
}
