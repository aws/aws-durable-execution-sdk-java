package com.amazonaws.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class DurableExecutionExceptionTest {

    @Test
    void testConstructorWithMessage() {
        var exception = new DurableExecutionException("Test message");

        assertEquals("Test message", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testConstructorWithMessageAndCause() {
        var cause = new RuntimeException("Cause message");
        var exception = new DurableExecutionException("Test message", cause);

        assertEquals("Test message", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void testConstructorWithMessageCauseAndStackTrace() {
        var cause = new RuntimeException("Cause message");
        var stackTrace = new StackTraceElement[] {
                new StackTraceElement("TestClass", "testMethod", "TestClass.java", 42)
        };
        var exception = new DurableExecutionException("Test message", cause, stackTrace);

        assertEquals("Test message", exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertArrayEquals(stackTrace, exception.getStackTrace());
    }

    @Test
    void testSerializeStackTrace() {
        var stackTrace = new StackTraceElement[] {
                new StackTraceElement("com.example.MyClass", "myMethod", "MyClass.java", 123),
                new StackTraceElement("com.example.OtherClass", "otherMethod", "OtherClass.java", 456)
        };

        var serialized = DurableExecutionException.serializeStackTrace(stackTrace);

        assertEquals(2, serialized.size());
        assertEquals("com.example.MyClass|myMethod|MyClass.java|123", serialized.get(0));
        assertEquals("com.example.OtherClass|otherMethod|OtherClass.java|456", serialized.get(1));
    }

    @Test
    void testDeserializeStackTrace() {
        var serialized = List.of(
                "com.example.MyClass|myMethod|MyClass.java|123",
                "com.example.OtherClass|otherMethod|OtherClass.java|456");

        var stackTrace = DurableExecutionException.deserializeStackTrace(serialized);

        assertEquals(2, stackTrace.length);
        assertEquals("com.example.MyClass", stackTrace[0].getClassName());
        assertEquals("myMethod", stackTrace[0].getMethodName());
        assertEquals("MyClass.java", stackTrace[0].getFileName());
        assertEquals(123, stackTrace[0].getLineNumber());
        assertEquals("com.example.OtherClass", stackTrace[1].getClassName());
        assertEquals("otherMethod", stackTrace[1].getMethodName());
        assertEquals("OtherClass.java", stackTrace[1].getFileName());
        assertEquals(456, stackTrace[1].getLineNumber());
    }

    @Test
    void testSerializeDeserializeRoundTrip() {
        var original = new StackTraceElement[] {
                new StackTraceElement("TestClass", "testMethod", "TestClass.java", 100)
        };

        var serialized = DurableExecutionException.serializeStackTrace(original);
        var deserialized = DurableExecutionException.deserializeStackTrace(serialized);

        assertArrayEquals(original, deserialized);
    }
}
