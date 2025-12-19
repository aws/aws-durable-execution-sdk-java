package com.amazonaws.lambda.durable.execution;

public enum ExecutionPhase {
    RUNNING(0),
    COMPLETE(1);

    private final int value;

    ExecutionPhase(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
