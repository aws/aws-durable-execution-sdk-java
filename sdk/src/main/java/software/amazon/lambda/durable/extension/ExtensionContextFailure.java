// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

/** Read-only failure information supplied to an extension CONTEXT error handler. */
public final class ExtensionContextFailure {
    private final Operation operation;
    private final Throwable originalException;
    private final List<ExtensionChildOperationSummary> childOperations;

    public ExtensionContextFailure(
            Operation operation, Throwable originalException, List<ExtensionChildOperationSummary> childOperations) {
        this.operation = Objects.requireNonNull(operation, "operation cannot be null");
        this.originalException = originalException;
        this.childOperations = List.copyOf(childOperations);
    }

    public ExtensionContextFailure(
            String contextName,
            String subType,
            Throwable originalException,
            ErrorObject error,
            List<ExtensionChildOperationSummary> childOperations) {
        this(
                Operation.builder()
                        .name(contextName)
                        .type(OperationType.CONTEXT)
                        .subType(subType)
                        .status(OperationStatus.FAILED)
                        .contextDetails(ContextDetails.builder().error(error).build())
                        .build(),
                originalException,
                childOperations);
    }

    public Operation operation() {
        return operation;
    }

    public String contextName() {
        return operation.name();
    }

    public String subType() {
        return operation.subType();
    }

    public Throwable originalException() {
        return originalException;
    }

    public ErrorObject error() {
        return operation.contextDetails() == null
                ? null
                : operation.contextDetails().error();
    }

    public List<ExtensionChildOperationSummary> childOperations() {
        return childOperations;
    }
}
