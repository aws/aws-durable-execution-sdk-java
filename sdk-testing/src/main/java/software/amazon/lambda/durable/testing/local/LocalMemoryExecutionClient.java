// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.local;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.TestOperation;
import software.amazon.lambda.durable.testing.TestResult;

/**
 * In-memory implementation of {@link DurableExecutionClient} for local testing. Stores operations and checkpoint state
 * in memory, simulating the durable execution backend without AWS infrastructure.
 */
public class LocalMemoryExecutionClient implements DurableExecutionClient {
    // use LinkedHashMap to keep insertion order
    private final Map<String, Operation> existingOperations = Collections.synchronizedMap(new LinkedHashMap<>());
    private final EventProcessor eventProcessor = new EventProcessor();
    private final List<OperationUpdate> operationUpdates = new CopyOnWriteArrayList<>();
    private final Map<String, Operation> updatedOperations = new HashMap<>();

    @Override
    public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
        operationUpdates.addAll(updates);
        updates.forEach(this::applyUpdate);

        var newToken = UUID.randomUUID().toString();

        CheckpointDurableExecutionResponse response;
        synchronized (updatedOperations) {
            response = CheckpointDurableExecutionResponse.builder()
                    .checkpointToken(newToken)
                    .newExecutionState(CheckpointUpdatedExecutionState.builder()
                            .operations(updatedOperations.values())
                            .build())
                    .build();

            // updatedOperations was copied into response, so clearing it is safe here
            updatedOperations.clear();
        }
        return response;
    }

    @Override
    public GetDurableExecutionStateResponse getExecutionState(String arn, String checkpointToken, String marker) {
        // local runner doesn't use this API at all
        throw new UnsupportedOperationException("getExecutionState is not supported");
    }

    /** Get all operation updates that have been sent to this client. Useful for testing and verification. */
    public List<OperationUpdate> getOperationUpdates() {
        return List.copyOf(operationUpdates);
    }

    /**
     * Advance all operations (simulates time passing for retries/waits).
     *
     * @return true if any operations were advanced, false otherwise
     */
    public boolean advanceTime() {
        var hasOperationsAdvanced = new AtomicBoolean(false);
        // forEach is safe as we're not adding or removing keys here
        existingOperations.forEach((key, op) -> {
            if (op.type() == OperationType.STEP && op.status() == OperationStatus.PENDING) {
                applyResult(op, OperationResult.ready());
                hasOperationsAdvanced.set(true);
            }

            if (op.type() == OperationType.WAIT && op.status() == OperationStatus.STARTED) {
                applyResult(op, OperationResult.succeeded(null));
                hasOperationsAdvanced.set(true);
            }
        });
        return hasOperationsAdvanced.get();
    }

    /** Completes a chained invoke operation with the given result, simulating a child Lambda response. */
    public void completeChainedInvoke(String name, OperationResult result) {
        var op = getOperationByName(name);
        if (op == null) {
            throw new IllegalStateException("Operation not found: " + name);
        }
        if (op.type() != OperationType.CHAINED_INVOKE || op.status() != OperationStatus.STARTED) {
            throw new IllegalStateException("Operation is not a CHAINED_INVOKE or not in STARTED state");
        }
        applyResult(op, result);
    }

    /** Returns the operation with the given name, or null if not found. */
    public Operation getOperationByName(String name) {
        return existingOperations.values().stream()
                .filter(op -> name.equals(op.name()))
                .findFirst()
                .orElse(null);
    }

    /** Returns all operations currently stored. */
    public List<Operation> getAllOperations() {
        return existingOperations.values().stream().toList();
    }

    /** Build TestResult from current state. */
    public <O> TestResult<O> toTestResult(DurableExecutionOutput output, TypeToken<O> resultType, SerDes serDes) {
        var testOperations = existingOperations.values().stream()
                .filter(op -> op.type() != OperationType.EXECUTION)
                .map(op -> new TestOperation(op, eventProcessor.getEventsForOperation(op.id()), serDes))
                .toList();
        return new TestResult<>(
                output.status(),
                output.result(),
                output.error(),
                testOperations,
                eventProcessor.getAllEvents(),
                resultType,
                serDes);
    }

    /** Simulate checkpoint failure by forcing an operation into STARTED state */
    public void resetCheckpointToStarted(String stepName) {
        var op = getOperationByName(stepName);
        if (op == null) {
            throw new IllegalStateException("Operation not found: " + stepName);
        }
        var startedOp = op.toBuilder().status(OperationStatus.STARTED).build();
        updateOperation(null, startedOp);
    }

    /** Simulate fire-and-forget checkpoint loss by removing the operation entirely */
    public void simulateFireAndForgetCheckpointLoss(String stepName) {
        var op = getOperationByName(stepName);
        if (op == null) {
            throw new IllegalStateException("Operation not found: " + stepName);
        }
        existingOperations.remove(op.id());
        synchronized (updatedOperations) {
            updatedOperations.remove(op.id());
        }
    }

    private void applyUpdate(OperationUpdate update) {
        var existingOp = existingOperations.get(update.id());
        var updatedOp = OperationProcessor.applyUpdate(update, existingOp);
        updateOperation(update, updatedOp);
    }

    /** Get callback ID for a named callback operation. */
    public String getCallbackId(String operationName) {
        var op = getOperationByName(operationName);
        if (op == null || op.callbackDetails() == null) {
            return null;
        }
        return op.callbackDetails().callbackId();
    }

    /** Simulate external system completing callback. */
    public void completeCallback(String callbackId, OperationResult result) {
        var op = findOperationByCallbackId(callbackId);
        if (op == null) {
            throw new IllegalStateException("Callback not found: " + callbackId);
        }
        if (op.type() != OperationType.CALLBACK || op.status() != OperationStatus.STARTED) {
            throw new IllegalStateException("Operation is not a CALLBACK or not in STARTED state");
        }

        applyResult(op, result);
    }

    private void applyResult(Operation op, OperationResult result) {
        // derive a possible action from the target status
        OperationAction action = deriveAction(result.operationStatus());
        if (action != null) {
            var update = OperationUpdate.builder()
                    .id(op.id())
                    .name(op.name())
                    .type(op.type())
                    .action(action)
                    .parentId(op.parentId())
                    .payload(result.result())
                    .error(result.error())
                    .build();
            applyUpdate(update);
        } else if (result.operationStatus() == OperationStatus.TIMED_OUT
                || result.operationStatus() == OperationStatus.STOPPED
                || result.operationStatus() == OperationStatus.READY) {
            var newOp = OperationProcessor.applyResult(op, result);
            updateOperation(null, newOp);
        } else {
            throw new IllegalStateException("Unsupported OperationStatus in result: " + result.operationStatus());
        }
    }

    private static OperationAction deriveAction(OperationStatus status) {
        return switch (status) {
            case STARTED -> OperationAction.START;
            case SUCCEEDED -> OperationAction.SUCCEED;
            case FAILED -> OperationAction.FAIL;
            case PENDING -> OperationAction.RETRY;
            case CANCELLED -> OperationAction.CANCEL;
            case READY, TIMED_OUT, STOPPED -> null; // no action for these operation statuses
            case UNKNOWN_TO_SDK_VERSION -> OperationAction.UNKNOWN_TO_SDK_VERSION; // Todo: Check this
        };
    }

    private Operation findOperationByCallbackId(String callbackId) {
        return existingOperations.values().stream()
                .filter(op -> op.callbackDetails() != null
                        && callbackId.equals(op.callbackDetails().callbackId()))
                .findFirst()
                .orElse(null);
    }

    private void updateOperation(OperationUpdate update, Operation op) {
        // update can be null when an operation is updated without an OperationUpdate
        if (update == null) {
            eventProcessor.processUpdate(op);
        } else {
            eventProcessor.processUpdate(update, op);
        }
        existingOperations.put(op.id(), op);
        synchronized (updatedOperations) {
            updatedOperations.put(op.id(), op);
        }
    }
}
