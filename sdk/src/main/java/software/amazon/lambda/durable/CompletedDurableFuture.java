// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/**
 * A {@link DurableFuture} that is already completed with a value.
 *
 * <p>Used for short-circuit cases (e.g., empty collection in map) where no checkpoint or async execution is needed.
 *
 * @param <T> the result type
 */
class CompletedDurableFuture<T> implements DurableFuture<T> {
    private final T value;

    CompletedDurableFuture(T value) {
        this.value = value;
    }

    @Override
    public T get() {
        return value;
    }
}
