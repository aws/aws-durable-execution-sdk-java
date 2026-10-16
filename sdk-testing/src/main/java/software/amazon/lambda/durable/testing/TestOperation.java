// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.awssdk.services.lambda.model.WaitDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;

/** Wrapper for AWS SDK Operation providing convenient access methods. */
public class TestOperation {
    private final Operation operation;
    private final List<Event> events;
    private final SerDes serDes;
    private final String executionArn;

    public TestOperation(Operation operation, SerDes serDes) {
        this(operation, List.of(), serDes);
    }

    public TestOperation(Operation operation, List<Event> events, SerDes serDes) {
        this(operation, events, serDes, null);
    }

    /**
     * Creates an operation snapshot with execution identity for context-aware result deserialization.
     *
     * @param executionArn the durable execution ARN, or null when unavailable for a manually constructed snapshot
     */
    public TestOperation(Operation operation, List<Event> events, SerDes serDes, String executionArn) {
        this.operation = operation;
        this.events = events;
        this.serDes = serDes;
        this.executionArn = executionArn;
    }

    /** Returns the raw history events associated with this operation. */
    public List<Event> getEvents() {
        return List.copyOf(events);
    }

    /** Returns the operation ID. */
    public String getId() {
        return operation.id();
    }

    /** Returns the operation name. */
    public String getName() {
        return operation.name();
    }

    /** Returns the current status of this operation (e.g. STARTED, SUCCEEDED, FAILED). */
    public OperationStatus getStatus() {
        return operation.status();
    }

    /** Returns the operation type (STEP, WAIT, CALLBACK, etc.). */
    public OperationType getType() {
        return operation.type();
    }

    /** Returns the operation's subtype */
    public String getSubtype() {
        return operation.subType();
    }

    /** Returns true if the operation has completed (either succeeded or failed). */
    public boolean isCompleted() {
        return ExecutionManager.isTerminalStatus(operation.status());
    }

    /** Returns the duration of the operation */
    public Duration getDuration() {
        return Duration.between(
                operation.startTimestamp(),
                operation.endTimestamp() != null ? operation.endTimestamp() : Instant.now());
    }

    /** Returns the step details, or null if this is not a step operation. */
    public StepDetails getStepDetails() {
        return operation.stepDetails();
    }

    /** Returns the wait details, or null if this is not a wait operation. */
    public WaitDetails getWaitDetails() {
        return operation.waitDetails();
    }

    /** Returns the callback details, or null if this is not a callback operation. */
    public CallbackDetails getCallbackDetails() {
        return operation.callbackDetails();
    }

    /** Returns the chained invoke details, or null if this is not a chained invoke operation. */
    public ChainedInvokeDetails getChainedInvokeDetails() {
        return operation.chainedInvokeDetails();
    }

    /** Returns the context details, or null if this operation is not a context. */
    public ContextDetails getContextDetails() {
        return operation.contextDetails();
    }

    /** Returns the execution details, or null if this operation is not an EXECUTION operation. */
    public ExecutionDetails getExecutionDetails() {
        return operation.executionDetails();
    }

    /** Deserializes and returns the step result as the given type. */
    public <T> T getStepResult(Class<T> type) {
        return getStepResult(TypeToken.get(type));
    }

    /** Deserializes and returns the step result using a TypeToken for generic types. */
    public <T> T getStepResult(TypeToken<T> type) {
        return getStepResult(type, serDes);
    }

    /**
     * Deserializes a step result using its operation-specific serializer. Does not change the runner's serializer for
     * handler input/output or other operations. Runners supply execution and operation identity automatically.
     *
     * <p>Filesystem serializers require the stored payload to be accessible from the test process. They cannot be
     * inferred from checkpoint data; pass the same serializer configuration used by the step.
     */
    public <T> T getStepResult(Class<T> type, SerDes resultSerDes) {
        return getStepResult(TypeToken.get(type), resultSerDes);
    }

    /**
     * Deserializes a generic step result using its operation-specific serializer. Legacy manually constructed snapshots
     * without an execution ARN use the serializer's context-free method; use the four-argument constructor when the
     * serializer requires context. Missing results return null without invoking the serializer.
     */
    public <T> T getStepResult(TypeToken<T> type, SerDes resultSerDes) {
        var details = operation.stepDetails();
        if (details == null || details.result() == null) {
            return null;
        }
        Objects.requireNonNull(resultSerDes, "resultSerDes");
        return executionArn == null
                ? resultSerDes.deserialize(details.result(), type)
                : resultSerDes.deserialize(
                        details.result(), type, new SerDesContext(executionArn, "operation/" + getId() + "/result"));
    }

    /** Returns the step error, or null if the step succeeded or this is not a step operation. */
    public ErrorObject getError() {
        var details = operation.stepDetails();
        return details != null ? details.error() : null;
    }

    /** Returns the current retry attempt number (1-based), defaulting to 1 if not available. */
    public int getAttempt() {
        var details = operation.stepDetails();
        return details != null && details.attempt() != null ? details.attempt() : 1;
    }
}
