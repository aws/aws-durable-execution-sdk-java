// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static java.util.Objects.requireNonNull;

/** Immutable SDK-owned description of an operation requesting chained-invoke propagation metadata. */
public final class PropagationInput {
    private final String executionArn;
    private final String operationId;
    private final String parentOperationId;
    private final String targetFunctionName;

    public PropagationInput(
            String executionArn, String operationId, String parentOperationId, String targetFunctionName) {
        this.executionArn = required(executionArn, "executionArn");
        this.operationId = required(operationId, "operationId");
        this.parentOperationId = parentOperationId;
        this.targetFunctionName = required(targetFunctionName, "targetFunctionName");
    }

    public String executionArn() {
        return executionArn;
    }

    public String operationId() {
        return operationId;
    }

    public String parentOperationId() {
        return parentOperationId;
    }

    public String targetFunctionName() {
        return targetFunctionName;
    }

    private static String required(String value, String name) {
        requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
