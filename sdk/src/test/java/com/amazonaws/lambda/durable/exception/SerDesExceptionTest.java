// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SerDesExceptionTest {

    @Test
    void testConstructorWithMessageAndCause() {
        var cause = new RuntimeException("Original error");
        var exception = new SerDesException("Serialization failed", cause);

        assertEquals("Serialization failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void testConstructorWithMessage() {
        var exception = new SerDesException("Deserialization failed");

        assertEquals("Deserialization failed", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testExtendsRuntimeException() {
        var exception = new SerDesException("Test message");

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(DurableExecutionException.class, exception);
    }
}
