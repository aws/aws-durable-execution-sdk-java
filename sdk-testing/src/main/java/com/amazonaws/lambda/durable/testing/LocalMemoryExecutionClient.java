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

    private void applyUpdate(OperationUpdate update) {
        var operation = toOperation(update);
        operations.put(update.id(), operation);
    }

    private Operation toOperation(OperationUpdate update) {
        // TODO: Currently we only use the StepDetails - should that depend on type actually?
        return Operation.builder()
                .id(update.id())
                .name(update.name())
                .type(update.type())
                .status(deriveStatus(update.action()))
                .stepDetails(StepDetails.builder().result(update.payload()).build())
                .build();
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
