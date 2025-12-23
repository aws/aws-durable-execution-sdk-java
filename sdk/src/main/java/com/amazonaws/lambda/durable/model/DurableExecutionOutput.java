package com.amazonaws.lambda.durable.model;

import com.amazonaws.lambda.durable.exception.StepFailedException;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

public record DurableExecutionOutput(
        ExecutionStatus status,
        String result,
        ErrorObject error) {
    public static DurableExecutionOutput success(String result) {
        return new DurableExecutionOutput(ExecutionStatus.SUCCEEDED, result, null);
    }

    public static DurableExecutionOutput pending() {
        return new DurableExecutionOutput(ExecutionStatus.PENDING, null, null);
    }

    public static DurableExecutionOutput failure(Throwable e) {
        var errorObject = ErrorObject.builder()
                .errorType(e.getClass().getSimpleName())
                .errorMessage(e.getMessage())
                .stackTrace(StepFailedException.serializeStackTrace(e.getStackTrace()))
                // TODO: Add errorData object once we support polymorphic object mappers
                .build();
        return new DurableExecutionOutput(ExecutionStatus.FAILED, null, errorObject);
    }
}
