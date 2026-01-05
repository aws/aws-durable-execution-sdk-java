// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

public class TestResult<O> {
    private final ExecutionStatus status;
    private final String resultPayload;
    private final ErrorObject error;
    private final Map<String, TestOperation> operations;
    private final List<Event> allEvents;
    private final Class<O> outputType;
    private final SerDes serDes;
    // For local runner - keep reference to storage for live view
    private final LocalMemoryExecutionClient storage;

    // Constructor for local runner (from LocalMemoryExecutionClient)
    TestResult(DurableExecutionOutput output, LocalMemoryExecutionClient storage) {
        this.status = output.status();
        this.resultPayload = output.result();
        this.error = output.error();
        this.serDes = new JacksonSerDes();
        this.outputType = null;
        this.storage = storage;
        this.allEvents = null; // Will get from storage
        this.operations = null; // Will get from storage
    }

    // Constructor for cloud runner (from HistoryEventProcessor)
    TestResult(ExecutionStatus status, String resultPayload, Map<String, TestOperation> operations, 
               List<Event> allEvents, Class<O> outputType, SerDes serDes) {
        this.status = status;
        this.resultPayload = resultPayload;
        this.error = null;
        this.operations = operations;
        this.allEvents = allEvents;
        this.outputType = outputType;
        this.serDes = serDes;
        this.storage = null;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public O getResult(Class<O> resultType) {
        if (status != ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("Execution did not succeed: " + status);
        }
        if (resultPayload == null) {
            return null;
        }
        return serDes.deserialize(resultPayload, resultType);
    }

    public Optional<ErrorObject> getError() {
        return Optional.ofNullable(error);
    }

    public List<TestOperation> getOperations() {
        if (storage != null) {
            // Local runner - live view from storage
            return storage.getAllOperations().stream()
                    .filter(op -> op.type() != OperationType.EXECUTION)
                    .map(op -> new TestOperation(op, storage.getEventsForOperation(op.id()), serDes))
                    .toList();
        }
        return List.copyOf(operations.values());
    }

    public TestOperation getOperation(String name) {
        if (storage != null) {
            // Local runner - live view from storage
            return storage.getAllOperations().stream()
                    .filter(op -> name.equals(op.name()))
                    .findFirst()
                    .map(op -> new TestOperation(op, storage.getEventsForOperation(op.id()), serDes))
                    .orElse(null);
        }
        return operations.get(name);
    }

    public List<Event> getHistoryEvents() {
        if (storage != null) {
            return storage.getAllEvents();
        }
        return List.copyOf(allEvents);
    }

    public List<Event> getEventsForOperation(String operationName) {
        if (storage != null) {
            // Local runner - need to find operation ID by name first
            var op = storage.getAllOperations().stream()
                    .filter(o -> operationName.equals(o.name()))
                    .findFirst()
                    .orElse(null);
            return op != null ? storage.getEventsForOperation(op.id()) : List.of();
        }
        var testOp = operations.get(operationName);
        return testOp != null ? testOp.getEvents() : List.of();
    }

    public boolean isSucceeded() {
        return status == ExecutionStatus.SUCCEEDED;
    }

    public boolean isFailed() {
        return status == ExecutionStatus.FAILED;
    }

    public List<TestOperation> getSucceededOperations() {
        return getOperations().stream()
                .filter(op -> op.getStatus() == OperationStatus.SUCCEEDED)
                .toList();
    }

    public List<TestOperation> getFailedOperations() {
        return getOperations().stream()
                .filter(op -> op.getStatus() == OperationStatus.FAILED)
                .toList();
    }

    public LocalMemoryExecutionClient getStorage() {
        return storage;
    }
}
