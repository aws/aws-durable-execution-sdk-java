// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

/** Exception thrown when a step with AT_MOST_ONCE_PER_RETRY semantics was started but interrupted before completion. */
public class StepInterruptedException extends DurableExecutionException {
    private final String operationId;
    private final String stepName;

    public StepInterruptedException(String operationId, String stepName) {
        super(formatMessage(operationId, stepName));
        this.operationId = operationId;
        this.stepName = stepName;
    }

    public StepInterruptedException(String stepId) {
        this(stepId, null);
    }

    public String getOperationId() {
        return operationId;
    }

    public String getStepName() {
        return stepName;
    }

    private static String formatMessage(String operationId, String stepName) {
        var message = String.format(
                "The step execution was initiated but failed to reach completion due to an interruption. Operation ID: %s",
                operationId);
        if (stepName != null) {
            message += String.format(", Step Name: %s", stepName);
        }
        return message;
    }
}
