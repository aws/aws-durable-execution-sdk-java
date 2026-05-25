// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.OperationType;

/**
 * Fine-grained classification of durable operations that pairs each subtype with its parent {@link OperationType}.
 *
 * <p>Used as the source of both the {@code type} and {@code subType} fields in checkpoint updates. Matches the
 * {@code OperationSubType} enum in the JavaScript and Python durable execution SDKs.
 */
public enum OperationSubType {
    STEP(OperationType.STEP, "Step"),
    WAIT(OperationType.WAIT, "Wait"),
    CALLBACK(OperationType.CALLBACK, "Callback"),
    CHAINED_INVOKE(OperationType.CHAINED_INVOKE, "ChainedInvoke"),
    RUN_IN_CHILD_CONTEXT(OperationType.CONTEXT, "RunInChildContext"),
    MAP(OperationType.CONTEXT, "Map"),
    MAP_ITERATION(OperationType.CONTEXT, "MapIteration"),
    PARALLEL(OperationType.CONTEXT, "Parallel"),
    PARALLEL_BRANCH(OperationType.CONTEXT, "ParallelBranch"),
    WAIT_FOR_CALLBACK(OperationType.CONTEXT, "WaitForCallback"),
    WAIT_FOR_CONDITION(OperationType.STEP, "WaitForCondition"),
    WITH_RETRY(OperationType.CONTEXT, "WithRetry");

    private final OperationType operationType;
    private final String value;

    OperationSubType(OperationType operationType, String value) {
        this.operationType = operationType;
        this.value = value;
    }

    /** Returns the parent {@link OperationType} for this subtype. */
    public OperationType getOperationType() {
        return operationType;
    }

    /** Returns the wire-format string value sent in checkpoint updates. */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
