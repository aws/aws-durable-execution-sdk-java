package com.amazonaws.lambda.durable.exception;

public class DurableExecutionException extends RuntimeException {
    private final String errorType;
    
    public DurableExecutionException(String message, String errorType) {
        super(message);
        this.errorType = errorType;
    }
    
    public DurableExecutionException(String message, String errorType, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }
    
    public String getErrorType() {
        return errorType;
    }
}
