package com.amazonaws.lambda.durable.model;

public enum ExecutionStatus {
    SUCCEEDED,
    FAILED,
    PENDING;
    
    @Override
    public String toString() {
        return name();
    }
}
