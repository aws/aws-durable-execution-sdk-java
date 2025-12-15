package com.amazonaws.lambda.durable.exception;

/**
 * Exception thrown when non-deterministic code is detected during replay.
 * This indicates that the workflow code has changed in a way that violates
 * determinism requirements between the original execution and replay.
 */
public class NonDeterministicExecutionException extends DurableExecutionException {
    
    public NonDeterministicExecutionException(String message) {
        super(message, "NonDeterministicExecution");
    }
}
