// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import software.amazon.awssdk.services.lambda.model.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class LocalMemoryExecutionClient implements DurableExecutionClient {
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private final AtomicReference<String> checkpointToken =
            new AtomicReference<>(UUID.randomUUID().toString());
    private final List<OperationUpdate> operationUpdates = new java.util.concurrent.CopyOnWriteArrayList<>();

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
    public GetDurableExecutionStateResponse getExecutionState(String arn, String marker) {
        return GetDurableExecutionStateResponse.builder()
                .operations(operations.values())
                .build();
    }

    /** Get all operation updates that have been sent to this client. Useful for testing and verification. */
    public List<OperationUpdate> getOperationUpdates() {
        return List.copyOf(operationUpdates);
    }
    /** Advance all operations (simulates time passing for retries/waits). */
    public void advanceReadyOperations() {
        operations.replaceAll((id, op) -> {
            if (op.status() == OperationStatus.PENDING) {
                return op.toBuilder().status(OperationStatus.READY).build();
            }
            if (op.status() == OperationStatus.STARTED && op.type() == OperationType.WAIT) {
                return op.toBuilder().status(OperationStatus.SUCCEEDED).build();
            }
            return op;
        });
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
    }

    private void applyUpdate(OperationUpdate update) {
        var operation = toOperation(update);
        operations.put(update.id(), operation);
    }

    private Operation toOperation(OperationUpdate update) {
        var builder = Operation.builder()
                .id(update.id())
                .name(update.name())
                .type(update.type())
                .status(deriveStatus(update.action()));

        switch (update.type()) {
            case WAIT -> builder.waitDetails(buildWaitDetails(update));
            case STEP -> builder.stepDetails(buildStepDetails(update));
        }

        return builder.build();
    }

    private WaitDetails buildWaitDetails(OperationUpdate update) {
        if (update.waitOptions() == null) return null;
        
        var scheduledEnd = Instant.now().plusSeconds(update.waitOptions().waitSeconds());
        return WaitDetails.builder()
                .scheduledEndTimestamp(scheduledEnd)
                .build();
    }

    private StepDetails buildStepDetails(OperationUpdate update) {
        var existingOp = operations.get(update.id());
        var existing = existingOp != null ? existingOp.stepDetails() : null;
        
        var detailsBuilder = existing != null ? existing.toBuilder() : StepDetails.builder();
        
        if (update.action() == OperationAction.RETRY) {
            var attempt = existing != null && existing.attempt() != null ? existing.attempt() + 1 : 1;
            detailsBuilder.attempt(attempt).error(update.error());
        }
        
        if (update.payload() != null) {
            detailsBuilder.result(update.payload());
        }
        
        return detailsBuilder.build();
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
