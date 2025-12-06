package com.amazonaws.lambda.durable.exception;

public class StepException extends DurableExecutionException {
    public StepException(String message) {
        super(message, "StepException");
    }
    
    public StepException(String message, Throwable cause) {
        super(message, "StepException", cause);
    }
}
