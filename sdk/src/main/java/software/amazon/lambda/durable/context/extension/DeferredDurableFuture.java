// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context.extension;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.context.BaseContext;
import software.amazon.lambda.durable.context.BaseContextImpl;

final class DeferredDurableFuture<T> implements DurableFuture<T> {
    private final AtomicBoolean bound = new AtomicBoolean();
    private final CompletableFuture<DurableFuture<T>> delegateFuture = new CompletableFuture<>();
    private final CompletableFuture<Void> completionSignal = new CompletableFuture<>();

    void bind(DurableFuture<T> delegate) {
        Objects.requireNonNull(delegate, "delegate cannot be null");
        if (!bound.compareAndSet(false, true)) {
            throw new IllegalStateException("A deferred durable future can only be bound once");
        }

        delegateFuture.complete(delegate);
        delegate.completionFuture().whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                completionSignal.complete(null);
            } else {
                completionSignal.completeExceptionally(throwable);
            }
        });
    }

    @Override
    public T get() {
        return awaitDelegate().get();
    }

    @Override
    public CompletableFuture<Void> completionFuture() {
        return completionSignal.thenApply(ignored -> null);
    }

    boolean isDone() {
        return completionSignal.isDone();
    }

    private DurableFuture<T> awaitDelegate() {
        var context = BaseContext.getCurrentContext();
        if (context instanceof BaseContextImpl contextImpl) {
            return contextImpl.getExecutionManager().awaitFuture(delegateFuture);
        }
        return delegateFuture.join();
    }
}
