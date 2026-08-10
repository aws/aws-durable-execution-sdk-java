// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.Objects;
import software.amazon.awssdk.services.lambda.model.OperationType;

/**
 * Identifies a durable operation by its unique ID, human-readable name, type and sub-type.
 *
 * @param operationId unique sequential identifier for the operation within an execution
 * @param name human-readable name for the operation
 * @param operationType backend primitive operation type
 * @param subType checkpoint subtype string
 */
public record OperationIdentifier(String operationId, String name, OperationType operationType, String subType) {
    public OperationIdentifier {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");
        Objects.requireNonNull(subType, "subType cannot be null");
        if (subType.isBlank()) {
            throw new IllegalArgumentException("subType cannot be blank");
        }
    }

    /** Creates an identifier for a standard SDK operation sub-type. */
    public static OperationIdentifier of(String operationId, String name, OperationSubType subType) {
        Objects.requireNonNull(subType, "subType cannot be null");
        return new OperationIdentifier(operationId, name, subType.getOperationType(), subType.getValue());
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
