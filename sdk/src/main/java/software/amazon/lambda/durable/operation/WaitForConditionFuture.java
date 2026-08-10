// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;

final class WaitForConditionFuture<T> implements DurableFuture<T> {
    private final DurableFuture<T> delegate;

    WaitForConditionFuture(DurableFuture<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    @Override
    public T get() {
        try {
            return delegate.get();
        } catch (StepFailedException e) {
            throw new WaitForConditionFailedException(e.getOperation());
        }
    }

    @Override
    public CompletableFuture<Void> completionFuture() {
        return delegate.completionFuture();
    }
}
