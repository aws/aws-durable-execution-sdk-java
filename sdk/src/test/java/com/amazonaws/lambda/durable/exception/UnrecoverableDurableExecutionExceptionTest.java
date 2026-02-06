// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UnrecoverableDurableExecutionExceptionTest {

    @Test
    void testNonDeterministicExecutionException() {
        var exception = new NonDeterministicExecutionException("Non-deterministic behavior detected");

        assertEquals("Non-deterministic behavior detected", exception.getMessage());
        assertEquals(
                "Non-deterministic behavior detected",
                exception.getErrorObject().errorMessage());
        assertEquals(
                "com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException",
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
                "com.amazonaws.lambda.durable.exception.IllegalDurableOperationException",
                exception.getErrorObject().errorType());
        assertNull(exception.getCause());
        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(DurableExecutionException.class, exception);
    }
}
