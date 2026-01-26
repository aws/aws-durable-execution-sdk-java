// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.OperationStatus;

public class TestResult<O> {
    private final ExecutionStatus status;
    private final String resultPayload;
    private final ErrorObject error;
    private final List<TestOperation> operations;
    private final Map<String, TestOperation> operationsByName;
    private final List<Event> allEvents;
    private final SerDes serDes;

    TestResult(
            ExecutionStatus status,
            String resultPayload,
            ErrorObject error,
            List<TestOperation> operations,
            List<Event> allEvents,
            SerDes serDes) {
        this.status = status;
        this.resultPayload = resultPayload;
        this.error = error;
        this.operations = List.copyOf(operations);
        this.operationsByName =
                operations.stream().collect(Collectors.toMap(TestOperation::getName, op -> op, (a, b) -> b));
        this.allEvents = List.copyOf(allEvents);
        this.serDes = serDes;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public <T> T getResult(Class<T> resultType) {
        if (status != ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("Execution did not succeed: " + status);
        }
        if (resultPayload == null) {
            return null;
        }
        return serDes.deserialize(resultPayload, TypeToken.get(resultType));
    }

    public Optional<ErrorObject> getError() {
        return Optional.ofNullable(error);
    }

    public List<TestOperation> getOperations() {
        return operations;
    }

    public TestOperation getOperation(String name) {
        return operationsByName.get(name);
    }

    public List<Event> getHistoryEvents() {
        return allEvents;
    }

    public List<Event> getEventsForOperation(String operationName) {
        var testOp = operationsByName.get(operationName);
        return testOp != null ? testOp.getEvents() : List.of();
    }

    public boolean isSucceeded() {
        return status == ExecutionStatus.SUCCEEDED;
    }

    public boolean isFailed() {
        return status == ExecutionStatus.FAILED;
    }

    public List<TestOperation> getSucceededOperations() {
        return operations.stream()
                .filter(op -> op.getStatus() == OperationStatus.SUCCEEDED)
                .toList();
    }

    public List<TestOperation> getFailedOperations() {
        return operations.stream()
                .filter(op -> op.getStatus() == OperationStatus.FAILED)
                .toList();
    }
}
