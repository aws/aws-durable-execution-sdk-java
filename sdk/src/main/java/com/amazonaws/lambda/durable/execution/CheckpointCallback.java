// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import java.util.List;
import software.amazon.awssdk.services.lambda.model.Operation;

/**
 * Functional interface for checkpoint completion notifications.
 *
 * <p>CheckpointManager calls this when a checkpoint completes, allowing ExecutionManager to update state and advance
 * phasers.
 */
@FunctionalInterface
interface CheckpointCallback {

    /**
     * Called when a checkpoint completes successfully.
     *
     * @param newOperations Updated operations from backend
     */
    void onComplete(List<Operation> newOperations);
}
