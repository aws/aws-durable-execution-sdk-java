// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.OperationType;

public record OperationIdentifier(
        String operationId, String name, OperationType operationType, OperationSubType subType) {
    public static OperationIdentifier of(String operationId, String name, OperationType type) {
        return new OperationIdentifier(operationId, name, type, null);
    }

    public static OperationIdentifier of(
            String operationId, String name, OperationType type, OperationSubType subType) {
        return new OperationIdentifier(operationId, name, type, subType);
    }
}
