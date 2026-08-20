// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.internal;

import java.util.Objects;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;

/**
 * Internal operation identifier that supports extension-defined subtype strings.
 *
 * @hidden
 */
@InternalApi
public record PrimitiveOperationIdentifier(
        String operationId, String name, OperationType operationType, String subType) {
    public PrimitiveOperationIdentifier {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");
        Objects.requireNonNull(subType, "subType cannot be null");
        if (subType.isBlank()) {
            throw new IllegalArgumentException("subType cannot be blank");
        }
    }

    /** Creates an identifier for a standard SDK operation subtype. */
    public static PrimitiveOperationIdentifier of(String operationId, String name, OperationSubType subType) {
        Objects.requireNonNull(subType, "subType cannot be null");
        return new PrimitiveOperationIdentifier(operationId, name, subType.getOperationType(), subType.getValue());
    }

    /** Converts the compatibility identifier used by the original operation engine. */
    public static PrimitiveOperationIdentifier from(OperationIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier cannot be null");
        return of(identifier.operationId(), identifier.name(), identifier.subType());
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
