// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.util.Objects;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

/** Read-only summary of a direct child operation involved in an extension CONTEXT failure. */
public final class ExtensionChildOperationSummary {
    private final Operation operation;
    private final OperationType operationType;
    private final String subType;
    private final OperationStatus status;
    private final ErrorObject error;

    public ExtensionChildOperationSummary(Operation operation) {
        this.operation = Objects.requireNonNull(operation, "operation cannot be null");
        operationType = operation.type();
        subType = operation.subType();
        status = operation.status();
        error = extractError(operation);
    }

    public ExtensionChildOperationSummary(
            OperationType operationType, String subType, OperationStatus status, ErrorObject error) {
        operation = null;
        this.operationType = operationType;
        this.subType = subType;
        this.status = status;
        this.error = error;
    }

    public Operation operation() {
        return operation;
    }

    public OperationType operationType() {
        return operationType;
    }

    public String subType() {
        return subType;
    }

    public OperationStatus status() {
        return status;
    }

    public ErrorObject error() {
        return error;
    }

    private static ErrorObject extractError(Operation operation) {
        if (operation.type() == null) {
            return null;
        }
        return switch (operation.type()) {
            case STEP ->
                operation.stepDetails() == null ? null : operation.stepDetails().error();
            case CHAINED_INVOKE ->
                operation.chainedInvokeDetails() == null
                        ? null
                        : operation.chainedInvokeDetails().error();
            case CALLBACK ->
                operation.callbackDetails() == null
                        ? null
                        : operation.callbackDetails().error();
            case CONTEXT ->
                operation.contextDetails() == null
                        ? null
                        : operation.contextDetails().error();
            default -> null;
        };
    }
}
