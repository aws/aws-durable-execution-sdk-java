// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.awssdk.services.lambda.model.*;

public class LocalMemoryExecutionClient implements DurableExecutionClient {
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private final Map<String, List<Operation>> paginatedOperations = new ConcurrentHashMap<>();
    private final Map<String, String> nextMarkers = new ConcurrentHashMap<>();
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
        if (marker == null || !paginatedOperations.containsKey(marker)) {
            return GetDurableExecutionStateResponse.builder()
                    .operations(operations.values())
                    .build();
        }
        var paginatedOperations = this.paginatedOperations.get(marker);
        var nextMarker = nextMarkers.get(marker);
        return GetDurableExecutionStateResponse.builder()
                .operations(paginatedOperations)
                .nextMarker(nextMarker)
                .build();
    }

    /** Setup pagination for testing. Call this to configure what operations should be returned for each marker. */
    public void setupPagination(String marker, List<Operation> ops, String nextMarker) {
        paginatedOperations.put(marker, ops);
        if (nextMarker != null) {
            nextMarkers.put(marker, nextMarker);
        }
    }

    /** Get all operation updates that have been sent to this client. Useful for testing and verification. */
    public List<OperationUpdate> getOperationUpdates() {
        return List.copyOf(operationUpdates);
    }

    /** Advance all PENDING operations to READY (simulates time passing for retries/waits). */
    public void advanceReadyOperations() {
        operations.replaceAll((id, op) -> {
            if (op.status() == OperationStatus.PENDING) {
                return op.toBuilder().status(OperationStatus.READY).build();
            }
            return op;
        });
    }
    
    /** Simulate an interrupted step by creating a STARTED operation with proper attempt tracking. */
    public void simulateInterruptedStep(String operationId, String name, int attempt) {
        var operation = Operation.builder()
                .id(operationId)
                .name(name)
                .type(OperationType.STEP)
                .status(OperationStatus.STARTED)
                .stepDetails(StepDetails.builder()
                    .attempt(attempt)
                    .build())
                .build();
        operations.put(operationId, operation);
    }
    
    /** Simulate a pending step waiting for retry. */
    public void simulatePendingStep(String operationId, String name, int attempt, java.time.Instant nextAttemptTime) {
        var operation = Operation.builder()
                .id(operationId)
                .name(name)
                .type(OperationType.STEP)
                .status(OperationStatus.PENDING)
                .stepDetails(StepDetails.builder()
                    .attempt(attempt)
                    .nextAttemptTimestamp(nextAttemptTime)
                    .build())
                .build();
        operations.put(operationId, operation);
    }

    private void applyUpdate(OperationUpdate update) {
        var operation = toOperation(update);
        operations.put(update.id(), operation);
    }

    private Operation toOperation(OperationUpdate update) {
        var stepDetailsBuilder = StepDetails.builder();
        
        // Set result payload
        if (update.payload() != null) {
            stepDetailsBuilder.result(update.payload());
        }
        
        // Set error details for failed operations
        if (update.error() != null) {
            stepDetailsBuilder.error(update.error());
        }
        
        // Set attempt number and next attempt timestamp for retries
        if (update.stepOptions() != null) {
            stepDetailsBuilder.attempt(getCurrentAttempt(update.id()) + 1);
            if (update.stepOptions().nextAttemptDelaySeconds() != null) {
                stepDetailsBuilder.nextAttemptTimestamp(
                    java.time.Instant.now().plusSeconds(update.stepOptions().nextAttemptDelaySeconds()));
            }
        } else {
            stepDetailsBuilder.attempt(getCurrentAttempt(update.id()));
        }

        return Operation.builder()
                .id(update.id())
                .name(update.name())
                .type(update.type())
                .status(deriveStatus(update.action()))
                .stepDetails(stepDetailsBuilder.build())
                .build();
    }
    
    private int getCurrentAttempt(String operationId) {
        var existing = operations.get(operationId);
        if (existing != null && existing.stepDetails() != null) {
            return existing.stepDetails().attempt();
        }
        return 0;
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
