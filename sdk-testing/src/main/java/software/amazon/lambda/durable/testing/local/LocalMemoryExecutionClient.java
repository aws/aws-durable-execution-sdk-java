// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.local;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.services.lambda.model.*;
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
            // advance pending retries
            if (op.status() == OperationStatus.PENDING) {
                hasOperationsAdvanced.set(true);
                var readyOp = op.toBuilder().status(OperationStatus.READY).build();
                updateOperation(readyOp);
            }

            // advance waits
            if (op.status() == OperationStatus.STARTED && op.type() == OperationType.WAIT) {
                var succeededOp =
                        op.toBuilder().status(OperationStatus.SUCCEEDED).build();
                // Generate WaitSucceeded event
                var update = OperationUpdate.builder()
                        .id(op.id())
                        .name(op.name())
                        .type(OperationType.WAIT)
                        .action(OperationAction.SUCCEED)
                        .build();
                eventProcessor.processUpdate(update, succeededOp);
                hasOperationsAdvanced.set(true);
                updateOperation(succeededOp);
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
        if (op.type() == OperationType.CHAINED_INVOKE
                && op.status() == OperationStatus.STARTED
                && op.name().equals(name)) {
            var newOp = op.toBuilder()
                    .status(result.operationStatus())
                    .chainedInvokeDetails(ChainedInvokeDetails.builder()
                            .result(result.result())
                            .error(result.error())
                            .build())
                    .build();
            var update = OperationUpdate.builder()
                    .id(op.id())
                    .name(op.name())
                    .type(OperationType.CHAINED_INVOKE)
                    .action(
                            result.operationStatus() == OperationStatus.SUCCEEDED
                                    ? OperationAction.SUCCEED
                                    : OperationAction.FAIL)
                    .build();
            eventProcessor.processUpdate(update, newOp);
            updateOperation(newOp);
        }
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
        updateOperation(startedOp);
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
        var operation = toOperation(update);
        updateOperation(operation);

        eventProcessor.processUpdate(update, operation);
    }

    private Operation toOperation(OperationUpdate update) {
        var builder = Operation.builder()
                .id(update.id())
                .name(update.name())
                .type(update.type())
                .subType(update.subType())
                .parentId(update.parentId())
                .status(deriveStatus(update.action()));

        switch (update.type()) {
            case WAIT -> builder.waitDetails(buildWaitDetails(update));
            case STEP -> builder.stepDetails(buildStepDetails(update));
            case CALLBACK -> builder.callbackDetails(buildCallbackDetails(update));
            case EXECUTION -> {} // No details needed for EXECUTION operations
            case CHAINED_INVOKE -> builder.chainedInvokeDetails(buildChainedInvokeDetails(update));
            case CONTEXT -> builder.contextDetails(buildContextDetails(update));
            case UNKNOWN_TO_SDK_VERSION ->
                throw new UnsupportedOperationException("UNKNOWN_TO_SDK_VERSION not supported");
        }

        return builder.build();
    }

    private ChainedInvokeDetails buildChainedInvokeDetails(OperationUpdate update) {
        if (update.chainedInvokeOptions() == null) {
            return null;
        }
        return ChainedInvokeDetails.builder()
                .result(update.payload())
                .error(update.error())
                .build();
    }

    private ContextDetails buildContextDetails(OperationUpdate update) {
        var detailsBuilder = ContextDetails.builder().result(update.payload()).error(update.error());

        if (update.contextOptions() != null
                && Boolean.TRUE.equals(update.contextOptions().replayChildren())) {
            detailsBuilder.replayChildren(true);
        }

        return detailsBuilder.build();
    }

    private WaitDetails buildWaitDetails(OperationUpdate update) {
        if (update.waitOptions() == null) return null;

        var scheduledEnd = Instant.now().plusSeconds(update.waitOptions().waitSeconds());
        return WaitDetails.builder().scheduledEndTimestamp(scheduledEnd).build();
    }

    private StepDetails buildStepDetails(OperationUpdate update) {
        var existingOp = existingOperations.get(update.id());
        var existing = existingOp != null ? existingOp.stepDetails() : null;

        var detailsBuilder = existing != null ? existing.toBuilder() : StepDetails.builder();
        var attempt = existing != null && existing.attempt() != null ? existing.attempt() + 1 : 1;

        if (update.action() == OperationAction.FAIL) {
            detailsBuilder.attempt(attempt).error(update.error());
        }

        if (update.action() == OperationAction.RETRY) {
            detailsBuilder
                    .attempt(attempt)
                    .error(update.error())
                    .nextAttemptTimestamp(
                            Instant.now().plusSeconds(update.stepOptions().nextAttemptDelaySeconds()));
        }

        if (update.payload() != null) {
            detailsBuilder.result(update.payload());
        }

        return detailsBuilder.build();
    }

    private CallbackDetails buildCallbackDetails(OperationUpdate update) {
        var existingOp = existingOperations.get(update.id());
        var existing = existingOp != null ? existingOp.callbackDetails() : null;

        // Preserve existing callbackId, or generate new one on START
        var callbackId =
                existing != null ? existing.callbackId() : UUID.randomUUID().toString();

        return CallbackDetails.builder()
                .callbackId(callbackId)
                .result(existing != null ? existing.result() : null)
                .build();
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
        var updated = op.toBuilder()
                .status(result.operationStatus())
                .callbackDetails(op.callbackDetails().toBuilder()
                        .result(result.result())
                        .error(result.error())
                        .build())
                .build();
        updateOperation(updated);
    }

    private Operation findOperationByCallbackId(String callbackId) {
        return existingOperations.values().stream()
                .filter(op -> op.callbackDetails() != null
                        && callbackId.equals(op.callbackDetails().callbackId()))
                .findFirst()
                .orElse(null);
    }

    private OperationStatus deriveStatus(OperationAction action) {
        return switch (action) {
            case START -> OperationStatus.STARTED;
            case SUCCEED -> OperationStatus.SUCCEEDED;
            case FAIL -> OperationStatus.FAILED;
            case RETRY -> OperationStatus.PENDING;
            case CANCEL -> OperationStatus.CANCELLED;
            case UNKNOWN_TO_SDK_VERSION -> OperationStatus.UNKNOWN_TO_SDK_VERSION; // Todo: Check this
        };
    }

    private void updateOperation(Operation op) {
        existingOperations.put(op.id(), op);
        synchronized (updatedOperations) {
            updatedOperations.put(op.id(), op);
        }
    }
}
