// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;

class StepInterruptedExceptionTest {
    private static final Operation OPERATION =
            Operation.builder().id("op-123").name("my-step").build();

    @Test
    void testConstructorWithOperationIdAndStepName() {
        var exception = new StepInterruptedException(OPERATION);

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(DurableExecutionException.class, exception);
        assertEquals("op-123", exception.getOperation().id());
        assertEquals("my-step", exception.getOperation().name());
        assertTrue(exception.getMessage().contains("Operation ID: op-123"));
        assertTrue(exception.getMessage().contains("Step Name: my-step"));
        assertTrue(exception.getMessage().contains("initiated but failed to reach completion due to an interruption"));
    }
}
