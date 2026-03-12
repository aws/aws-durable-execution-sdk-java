// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.OperationType;

/**
 * Identifies a durable operation by its unique ID, human-readable name, type, and optional sub-type.
 *
 * @param operationId unique sequential identifier for the operation within an execution
 * @param name human-readable name for the operation
 * @param operationType the kind of operation (STEP, WAIT, CALLBACK, etc.)
 * @param subType optional sub-type for operations that need further classification (e.g. child contexts)
 */
public record OperationIdentifier(
        String operationId, String name, OperationType operationType, OperationSubType subType) {

    /** Creates an identifier without a sub-type. */
    public static OperationIdentifier of(String operationId, String name, OperationType type) {
        return new OperationIdentifier(operationId, name, type, null);
    }

    /** Creates an identifier with a sub-type. */
    public static OperationIdentifier of(
            String operationId, String name, OperationType type, OperationSubType subType) {
        return new OperationIdentifier(operationId, name, type, subType);
    }
}
