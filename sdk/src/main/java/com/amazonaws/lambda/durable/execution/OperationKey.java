// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import software.amazon.awssdk.services.lambda.model.Operation;

/**
 * Composite key for scoped operation lookups in {@link ExecutionManager}.
 *
 * <p>Child contexts have their own operation counters starting at 1, so operation IDs alone are not unique across
 * contexts. This record combines the parent context ID (from the backend's {@code Operation.parentId()}) with the
 * operation ID to form a unique key.
 *
 * <p>For root-level operations, {@code parentId} is {@code null}. For operations within a child context,
 * {@code parentId} is the CONTEXT operation's ID.
 */
public record OperationKey(String parentId, String operationId) {

    /** Creates an OperationKey from explicit parentId and operationId values. */
    public static OperationKey of(String parentId, String operationId) {
        return new OperationKey(parentId, operationId);
    }

    /** Creates an OperationKey from an AWS SDK Operation model object. */
    public static OperationKey fromOperation(Operation op) {
        return new OperationKey(op.parentId(), op.id());
    }
}
