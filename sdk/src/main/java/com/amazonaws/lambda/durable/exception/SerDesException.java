package com.amazonaws.lambda.durable.exception;

/**
 * Exception thrown when serialization or deserialization fails.
 */
public class SerDesException extends DurableExecutionException {
    public SerDesException(String message, Throwable cause) {
        super(message, cause);
    }

    public SerDesException(String message) {
        super(message);
    }
}
