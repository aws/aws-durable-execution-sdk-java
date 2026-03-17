// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import java.util.List;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.awssdk.services.lambda.model.WaitDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.SerDes;

/** Wrapper for AWS SDK Operation providing convenient access methods. */
public class TestOperation {
    private final Operation operation;
    private final List<Event> events;
    private final SerDes serDes;

    TestOperation(Operation operation, SerDes serDes) {
        this(operation, List.of(), serDes);
    }

    TestOperation(Operation operation, List<Event> events, SerDes serDes) {
        this.operation = operation;
        this.events = events;
        this.serDes = serDes;
    }

    /** Returns the raw history events associated with this operation. */
    public List<Event> getEvents() {
        return List.copyOf(events);
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

    /** Deserializes and returns the step result as the given type. */
    public <T> T getStepResult(Class<T> type) {
        return getStepResult(TypeToken.get(type));
    }

    /** Deserializes and returns the step result using a TypeToken for generic types. */
    public <T> T getStepResult(TypeToken<T> type) {
        var details = operation.stepDetails();
        if (details == null || details.result() == null) {
            return null;
        }
        return serDes.deserialize(details.result(), type);
    }

    /** Returns the step error, or null if the step succeeded or this is not a step operation. */
    public ErrorObject getError() {
        var details = operation.stepDetails();
        return details != null ? details.error() : null;
    }

    /** Returns the current retry attempt number (0-based), defaulting to 0 if not available. */
    public int getAttempt() {
        var details = operation.stepDetails();
        return details != null && details.attempt() != null ? details.attempt() : 0;
    }
}
