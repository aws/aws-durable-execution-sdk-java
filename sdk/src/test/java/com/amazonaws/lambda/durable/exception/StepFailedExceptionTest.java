// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StepFailedExceptionTest {

    @Test
    void testConstructorWithMessageAndCause() {
        var cause = new RuntimeException("Original error");
        var exception = new StepFailedException("Step failed", cause);

        assertEquals("Step failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void testConstructorWithMessageCauseAndStackTrace() {
        var cause = new RuntimeException("Original error");
        var stackTrace =
                new StackTraceElement[] {new StackTraceElement("StepClass", "executeStep", "StepClass.java", 50)};
        var exception = new StepFailedException("Step failed", cause, stackTrace);

        assertEquals("Step failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertArrayEquals(stackTrace, exception.getStackTrace());
    }

    @Test
    void testExtendsRuntimeException() {
        var cause = new RuntimeException("Test");
        var exception = new StepFailedException("Test message", cause);

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(DurableExecutionException.class, exception);
    }
}
