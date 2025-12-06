package com.amazonaws.lambda.durable.model;

public record DurableExecutionOutput(
    ExecutionStatus status,
    String result,
    ErrorObject error
) {
    public static DurableExecutionOutput success(String result) {
        return new DurableExecutionOutput(ExecutionStatus.SUCCEEDED, result, null);
    }
    
    public static DurableExecutionOutput pending() {
        return new DurableExecutionOutput(ExecutionStatus.PENDING, null, null);
    }
    
    public static DurableExecutionOutput failure(ErrorObject error) {
        return new DurableExecutionOutput(ExecutionStatus.FAILED, null, error);
    }
}
