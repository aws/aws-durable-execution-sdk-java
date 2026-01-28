// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.EventType;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionHistoryRequest;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

/**
 * Handle for an asynchronously executing durable function. Allows incremental polling and inspection of execution
 * state.
 */
public class AsyncExecution<O> {
    private final String executionArn;
    private final LambdaClient lambdaClient;
    private final Class<O> outputType;
    private final Duration pollInterval;
    private final Duration timeout;
    private final HistoryEventProcessor processor;
    private List<Event> currentHistory;
    private TestResult<O> currentResult;

    public AsyncExecution(
            String executionArn,
            LambdaClient lambdaClient,
            Class<O> outputType,
            Duration pollInterval,
            Duration timeout) {
        this.executionArn = executionArn;
        this.lambdaClient = lambdaClient;
        this.outputType = outputType;
        this.pollInterval = pollInterval;
        this.timeout = timeout;
        this.processor = new HistoryEventProcessor();
    }

    /**
     * Poll execution history until the given condition is met.
     *
     * @param condition predicate to test on each poll
     * @return this execution for chaining
     */
    public AsyncExecution<O> pollUntil(Predicate<AsyncExecution<O>> condition) {
        var startTime = Instant.now();

        while (Duration.between(startTime, Instant.now()).compareTo(timeout) < 0) {
            refreshHistory();

            if (condition.test(this)) {
                return this;
            }

            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Polling interrupted", e);
            }
        }

        throw new RuntimeException("Condition not met within timeout of " + timeout);
    }

    /**
     * Poll until execution completes and return the final result.
     *
     * @return test result with execution status and output
     */
    public TestResult<O> pollUntilComplete() {
        pollUntil(AsyncExecution::isComplete);
        return currentResult;
    }

    /** Check if execution has completed (succeeded or failed). */
    public boolean isComplete() {
        if (currentHistory == null) {
            return false;
        }
        return currentHistory.stream().anyMatch(e -> {
            var eventType = e.eventType();
            return EventType.EXECUTION_SUCCEEDED.equals(eventType) || EventType.EXECUTION_FAILED.equals(eventType);
        });
    }

    /** Check if an operation with the given name exists. */
    public boolean hasOperation(String name) {
        if (currentResult == null) {
            return false;
        }
        return currentResult.getOperations().stream().anyMatch(op -> name.equals(op.getName()));
    }

    /** Check if a callback operation with the given name exists and is started. */
    public boolean hasCallback(String name) {
        if (currentHistory == null) {
            return false;
        }
        // Look for CallbackStarted event with this name
        return currentHistory.stream()
                .anyMatch(e -> name.equals(e.name()) && EventType.CALLBACK_STARTED.equals(e.eventType()));
    }

    /**
     * Get the callback ID for a callback operation.
     *
     * @param operationName name of the callback operation
     * @return callback ID
     * @throws IllegalStateException if no callback found for operation
     */
    public String getCallbackId(String operationName) {
        if (currentResult == null) {
            throw new IllegalStateException("No history available - call pollUntil first");
        }

        var operation = currentResult.getOperations().stream()
                .filter(op -> operationName.equals(op.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No operation found with name: " + operationName));

        var callbackDetails = operation.getCallbackDetails();
        if (callbackDetails == null || callbackDetails.callbackId() == null) {
            throw new IllegalStateException(
                    "Operation '" + operationName + "' is not a callback or has no callback ID");
        }

        return callbackDetails.callbackId();
    }

    /** Get details for a specific operation. */
    public TestOperation getOperation(String name) {
        if (currentResult == null) {
            throw new IllegalStateException("No history available - call pollUntil first");
        }
        return currentResult.getOperation(name);
    }

    /** Get all operations in the execution. */
    public List<TestOperation> getOperations() {
        if (currentResult == null) {
            throw new IllegalStateException("No history available - call pollUntil first");
        }
        return currentResult.getOperations();
    }

    /** Get current execution status. */
    public ExecutionStatus getStatus() {
        if (currentResult == null) {
            return ExecutionStatus.PENDING;
        }
        return currentResult.getStatus();
    }

    /** Get the execution ARN. */
    public String getExecutionArn() {
        return executionArn;
    }

    private void refreshHistory() {
        try {
            var request = GetDurableExecutionHistoryRequest.builder()
                    .durableExecutionArn(executionArn)
                    .includeExecutionData(true)
                    .build();
            var response = lambdaClient.getDurableExecutionHistory(request);
            this.currentHistory = response.events();
            this.currentResult = processor.processEvents(currentHistory, outputType);
        } catch (ResourceNotFoundException e) {
            // Execution doesn't exist yet - this can happen immediately after async invoke
            // Leave currentHistory as null, pollUntil will retry
            this.currentHistory = null;
            this.currentResult = null;
        }
    }
}
