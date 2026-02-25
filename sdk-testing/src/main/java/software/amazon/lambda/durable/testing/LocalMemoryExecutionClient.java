// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

public class LocalMemoryExecutionClient implements DurableExecutionClient {
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private final List<Event> allEvents = new CopyOnWriteArrayList<>();
    private final EventProcessor eventProcessor = new EventProcessor();
    private final SerDes serDes = new JacksonSerDes();
    private final AtomicReference<String> checkpointToken =
            new AtomicReference<>(UUID.randomUUID().toString());
    private final List<OperationUpdate> operationUpdates = new CopyOnWriteArrayList<>();

    @Override
    public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
        operationUpdates.addAll(updates);
        updates.forEach(this::applyUpdate);

        var newToken = UUID.randomUUID().toString();
        checkpointToken.set(newToken);

        return CheckpointDurableExecutionResponse.builder()
                .checkpointToken(newToken)
                .newExecutionState(CheckpointUpdatedExecutionState.builder()
                        .operations(operations.values())
                        .build())
                .build();
    }

    @Override
    public GetDurableExecutionStateResponse getExecutionState(String arn, String checkpointToken, String marker) {
        return GetDurableExecutionStateResponse.builder()
                .operations(operations.values())
                .build();
    }

    /** Get all operation updates that have been sent to this client. Useful for testing and verification. */
    public List<OperationUpdate> getOperationUpdates() {
        return List.copyOf(operationUpdates);
    }

    /** Get all events in order. */
    public List<Event> getAllEvents() {
        return List.copyOf(allEvents);
    }

    /** Get events for a specific operation. */
    public List<Event> getEventsForOperation(String operationId) {
        return allEvents.stream().filter(e -> operationId.equals(e.id())).toList();
    }

    /**
     * Advance all operations (simulates time passing for retries/waits).
     *
     * @return true if any operations were advanced, false otherwise
     */
    public boolean advanceReadyOperations() {
        var replaced = new AtomicBoolean(false);
        operations.replaceAll((key, op) -> {
            if (op.status() == OperationStatus.PENDING) {
                replaced.set(true);
                return op.toBuilder().status(OperationStatus.READY).build();
            }
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
                var event = eventProcessor.processUpdate(update, succeededOp);
                allEvents.add(event);
                replaced.set(true);
                return succeededOp;
            }
            return op;
        });
        return replaced.get();
    }

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
            var event = eventProcessor.processUpdate(update, newOp);
            allEvents.add(event);
            operations.put(compositeKey(op.parentId(), op.id()), newOp);
        }
    }

    public Operation getOperationByName(String name) {
        return operations.values().stream()
                .filter(op -> name.equals(op.name()))
                .findFirst()
                .orElse(null);
    }

    public List<Operation> getAllOperations() {
        return operations.values().stream().toList();
    }

    public void reset() {
        operations.clear();
        allEvents.clear();
    }

    /** Build TestResult from current state. */
    public <O> TestResult<O> toTestResult(DurableExecutionOutput output) {
        var testOperations = operations.values().stream()
                .filter(op -> op.type() != OperationType.EXECUTION)
                .map(op -> new TestOperation(op, getEventsForOperation(op.id()), serDes))
                .toList();
        return new TestResult<>(
                output.status(), output.result(), output.error(), testOperations, new ArrayList<>(allEvents), serDes);
    }

    /** Simulate checkpoint failure by forcing an operation into STARTED state */
    public void resetCheckpointToStarted(String stepName) {
        var op = getOperationByName(stepName);
        if (op == null) {
            throw new IllegalStateException("Operation not found: " + stepName);
        }
        var startedOp = op.toBuilder().status(OperationStatus.STARTED).build();
        operations.put(compositeKey(op.parentId(), op.id()), startedOp);
    }

    /** Simulate fire-and-forget checkpoint loss by removing the operation entirely */
    public void simulateFireAndForgetCheckpointLoss(String stepName) {
        var op = getOperationByName(stepName);
        if (op == null) {
            throw new IllegalStateException("Operation not found: " + stepName);
        }
        operations.remove(compositeKey(op.parentId(), op.id()));
    }

    private void applyUpdate(OperationUpdate update) {
        var operation = toOperation(update);
        var key = compositeKey(update.parentId(), update.id());
        operations.put(key, operation);

        var event = eventProcessor.processUpdate(update, operation);
        allEvents.add(event);
    }

    private static String compositeKey(String parentId, String operationId) {
        return (parentId != null ? parentId : "") + ":" + operationId;
    }

    private Operation toOperation(OperationUpdate update) {
        var builder = Operation.builder()
                .id(update.id())
                .name(update.name())
                .type(update.type())
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
        var key = compositeKey(update.parentId(), update.id());
        var existingOp = operations.get(key);
        var existing = existingOp != null ? existingOp.stepDetails() : null;

        var detailsBuilder = existing != null ? existing.toBuilder() : StepDetails.builder();

        if (update.action() == OperationAction.RETRY || update.action() == OperationAction.FAIL) {
            var attempt = existing != null && existing.attempt() != null ? existing.attempt() + 1 : 1;
            detailsBuilder.attempt(attempt).error(update.error());
        }

        if (update.payload() != null) {
            detailsBuilder.result(update.payload());
        }

        return detailsBuilder.build();
    }

    private CallbackDetails buildCallbackDetails(OperationUpdate update) {
        var key = compositeKey(update.parentId(), update.id());
        var existingOp = operations.get(key);
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

    /** Simulate external system completing callback successfully. */
    public void completeCallback(String callbackId, String result) {
        var op = findOperationByCallbackId(callbackId);
        if (op == null) {
            throw new IllegalStateException("Callback not found: " + callbackId);
        }
        var updated = op.toBuilder()
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(op.callbackDetails().toBuilder().result(result).build())
                .build();
        operations.put(compositeKey(op.parentId(), op.id()), updated);
    }

    /** Simulate external system failing callback. */
    public void failCallback(String callbackId, ErrorObject error) {
        var op = findOperationByCallbackId(callbackId);
        if (op == null) {
            throw new IllegalStateException("Callback not found: " + callbackId);
        }
        var updated = op.toBuilder()
                .status(OperationStatus.FAILED)
                .callbackDetails(op.callbackDetails().toBuilder().error(error).build())
                .build();
        operations.put(compositeKey(op.parentId(), op.id()), updated);
    }

    /** Simulate callback timeout. */
    public void timeoutCallback(String callbackId) {
        var op = findOperationByCallbackId(callbackId);
        if (op == null) {
            throw new IllegalStateException("Callback not found: " + callbackId);
        }
        var updated = op.toBuilder().status(OperationStatus.TIMED_OUT).build();
        operations.put(compositeKey(op.parentId(), op.id()), updated);
    }

    private Operation findOperationByCallbackId(String callbackId) {
        return operations.values().stream()
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
            case UNKNOWN_TO_SDK_VERSION -> null; // Todo: Check this
        };
    }
}
