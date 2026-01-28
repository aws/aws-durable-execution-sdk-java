// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.operation.DurableOperation;

/**
 * Result of creating a callback, containing the callback ID and providing access to the result. Extends DurableFuture
 * so callbacks can be processed the same way as other futures.
 */
public class DurableCallbackFuture<T> extends DurableFuture<T> {
    private final String callbackId;

    public DurableCallbackFuture(String callbackId, DurableOperation<T> operation) {
        super(operation);
        this.callbackId = callbackId;
    }

    public String callbackId() {
        return callbackId;
    }
}
