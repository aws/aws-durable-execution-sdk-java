// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.operation.DurableOperation;

public class DurableFuture<T> {
    private final DurableOperation<T> operation;

    public DurableFuture(DurableOperation<T> operation) {
        this.operation = operation;
    }

    /**
     * Blocks until the operation completes and returns the result.
     *
     * <p>This delegates to operation.get() which handles: - Phaser blocking (arriveAndAwaitAdvance) - Thread
     * deregistration (allows suspension) - Thread reactivation (resumes execution) - Result retrieval
     *
     * @return the operation result
     */
    public T get() {
        return operation.get();
    }
}
