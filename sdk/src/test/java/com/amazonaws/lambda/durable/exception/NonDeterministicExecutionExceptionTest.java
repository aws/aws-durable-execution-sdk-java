// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NonDeterministicExecutionExceptionTest {

    @Test
    void testConstructorWithMessage() {
        var exception = new NonDeterministicExecutionException("Non-deterministic behavior detected");

        assertEquals("Non-deterministic behavior detected", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testExtendsRuntimeException() {
        var exception = new NonDeterministicExecutionException("Test message");

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(DurableExecutionException.class, exception);
    }
}
