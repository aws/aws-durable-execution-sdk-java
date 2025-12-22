package com.amazonaws.lambda.durable.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepInterruptedExceptionTest {

    @Test
    void testConstructorWithOperationIdAndStepName() {
        var exception = new StepInterruptedException("op-123", "my-step");
        
        assertEquals("op-123", exception.getOperationId());
        assertEquals("my-step", exception.getStepName());
        assertTrue(exception.getMessage().contains("Operation ID: op-123"));
        assertTrue(exception.getMessage().contains("Step Name: my-step"));
    }

    @Test
    void testConstructorWithOperationIdOnly() {
        var exception = new StepInterruptedException("op-456");
        
        assertEquals("op-456", exception.getOperationId());
        assertNull(exception.getStepName());
        assertTrue(exception.getMessage().contains("Operation ID: op-456"));
        assertFalse(exception.getMessage().contains("Step Name:"));
    }

    @Test
    void testMessageFormatWithNullStepName() {
        var exception = new StepInterruptedException("op-789", null);
        
        assertEquals("op-789", exception.getOperationId());
        assertNull(exception.getStepName());
        assertTrue(exception.getMessage().contains("Operation ID: op-789"));
        assertFalse(exception.getMessage().contains("Step Name:"));
    }

    @Test
    void testMessageContainsInterruptionText() {
        var exception = new StepInterruptedException("op-001", "test-step");
        
        assertTrue(exception.getMessage().contains("initiated but failed to reach completion due to an interruption"));
    }

    @Test
    void testExtendsRuntimeException() {
        var exception = new StepInterruptedException("op-123");
        
        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(DurableExecutionException.class, exception);
    }
}
