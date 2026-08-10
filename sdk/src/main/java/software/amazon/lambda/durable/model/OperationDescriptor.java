// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.Objects;
import software.amazon.awssdk.services.lambda.model.OperationType;

/**
 * Operation identity that permits extension-defined subtype strings.
 *
 * @param operationId globally unique operation ID
 * @param name human-readable operation name
 * @param operationType backend primitive operation type
 * @param subType checkpoint subtype string
 */
public record OperationDescriptor(String operationId, String name, OperationType operationType, String subType) {
    public OperationDescriptor {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");
        Objects.requireNonNull(subType, "subType cannot be null");
        if (subType.isBlank()) {
            throw new IllegalArgumentException("subType cannot be blank");
        }
    }

    /** Converts an existing enum-based identity to a descriptor. */
    public static OperationDescriptor from(OperationIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier cannot be null");
        return new OperationDescriptor(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType(),
                identifier.subType().getValue());
    }

    /** Returns the matching SDK subtype, or {@code null} for an extension-defined value. */
    public OperationSubType standardSubType() {
        for (var candidate : OperationSubType.values()) {
            if (candidate.getOperationType() == operationType
                    && candidate.getValue().equals(subType)) {
                return candidate;
            }
        }
        return null;
    }
}
