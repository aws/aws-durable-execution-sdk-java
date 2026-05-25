// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.OperationType;

/**
 * Identifies a durable operation by its unique ID, human-readable name, type and sub-type.
 *
 * @param operationId unique sequential identifier for the operation within an execution
 * @param name human-readable name for the operation
 * @param subType the operation sub-type which also determines the operation type
 */
public record OperationIdentifier(String operationId, String name, OperationSubType subType) {

    /** Returns the operation type derived from the sub-type. */
    public OperationType operationType() {
        return subType.getOperationType();
    }

    /** Creates an identifier with the given sub-type. */
    public static OperationIdentifier of(String operationId, String name, OperationSubType subType) {
        return new OperationIdentifier(operationId, name, subType);
    }
}
