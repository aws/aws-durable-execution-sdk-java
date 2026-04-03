// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.EventType;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Represents the result of a durable execution, providing access to the execution status, output, operations, and
 * history events.
 *
 * @param <O> the handler output type
 */
public class TestResult<O> {
    private static final Set<OperationStatus> FAIL_OPERATION_STATUS = Set.of(
            OperationStatus.FAILED, OperationStatus.CANCELLED, OperationStatus.TIMED_OUT, OperationStatus.STOPPED);
    private final ExecutionStatus status;
    private final String resultPayload;
    private final ErrorObject error;
    private final List<TestOperation> operations;
    private final Map<String, TestOperation> operationsByName;
    private final List<Event> allEvents;
    private final SerDes serDes;

    public TestResult(
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

    /** Returns the execution status (SUCCEEDED, FAILED, or PENDING). */
    public ExecutionStatus getStatus() {
        if (status == ExecutionStatus.SUCCEEDED && error != null) {
            throw new IllegalStateException(
                    "Execution succeeded while invocation failed with: " + error.errorMessage());
        }
        return status;
    }

    /** Deserializes and returns the execution output, throwing if the execution did not succeed. */
    public <T> T getResult(Class<T> resultType) {
        return getResult(TypeToken.get(resultType));
    }

    /** Deserializes and returns the execution output using a TypeToken for generic types. */
    public <T> T getResult(TypeToken<T> resultType) {
        if (status != ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("Execution did not succeed: " + status);
        }
        if (resultPayload == null || resultPayload.isEmpty()) {
            var lastEvent = allEvents.get(allEvents.size() - 1);
            if (lastEvent.eventType() == EventType.EXECUTION_SUCCEEDED) {
                return serDes.deserialize(
                        lastEvent.executionSucceededDetails().result().payload(), resultType);
            }
            return null;
        }
        return serDes.deserialize(resultPayload, resultType);
    }

    /** Returns the execution error, if present. */
    public Optional<ErrorObject> getError() {
        return Optional.ofNullable(error);
    }

    /** Returns all operations from the execution. */
    public List<TestOperation> getOperations() {
        return operations;
    }

    /** Returns the {@link TestOperation} with the given name, or null if not found. */
    public TestOperation getOperation(String name) {
        return operationsByName.get(name);
    }

    /** Returns all raw history events from the execution. */
    public List<Event> getHistoryEvents() {
        return allEvents;
    }

    /** Returns the raw history events for the given operation name, or an empty list if not found. */
    public List<Event> getEventsForOperation(String operationName) {
        var testOp = operationsByName.get(operationName);
        return testOp != null ? testOp.getEvents() : List.of();
    }

    /** Returns true if the execution completed successfully. */
    public boolean isSucceeded() {
        return status == ExecutionStatus.SUCCEEDED;
    }

    /** Returns true if the execution failed. */
    public boolean isFailed() {
        return status == ExecutionStatus.FAILED;
    }

    /** Returns all operations that completed successfully. */
    public List<TestOperation> getSucceededOperations() {
        return operations.stream()
                .filter(op -> op.getStatus() == OperationStatus.SUCCEEDED)
                .toList();
    }

    /** Returns all operations that failed, were cancelled, timed out, or stopped. */
    public List<TestOperation> getFailedOperations() {
        return operations.stream()
                .filter(op -> FAIL_OPERATION_STATUS.contains(op.getStatus()))
                .toList();
    }
}
