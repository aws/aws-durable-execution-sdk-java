// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableFuture;

class DeferredDurableFutureTest {
    @Test
    void getWaitsForBindingThenDelegates() throws Exception {
        var deferred = new DurableConcurrencyOperation.DeferredDurableFuture<String>();
        var getStarted = new CountDownLatch(1);
        var result = CompletableFuture.supplyAsync(() -> {
            getStarted.countDown();
            return deferred.get();
        });
        getStarted.await();

        assertFalse(result.isDone());

        deferred.bind(new TestFuture<>("result"));

        assertEquals("result", result.join());
    }

    @Test
    void completionSignalObtainedBeforeBindingTracksDelegate() {
        var deferred = new DurableConcurrencyOperation.DeferredDurableFuture<String>();
        var completion = deferred.completionFuture();
        var delegate = new TestFuture<>("result");

        deferred.bind(delegate);
        assertFalse(completion.isDone());

        delegate.complete();

        completion.join();
    }

    @Test
    void bindRejectsASecondDelegate() {
        var deferred = new DurableConcurrencyOperation.DeferredDurableFuture<String>();
        deferred.bind(new TestFuture<>("first"));

        var exception = assertThrows(IllegalStateException.class, () -> deferred.bind(new TestFuture<>("second")));

        assertEquals("A deferred durable future can only be bound once", exception.getMessage());
    }

    private static final class TestFuture<T> implements DurableFuture<T> {
        private final T result;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private TestFuture(T result) {
            this.result = result;
        }

        @Override
        public T get() {
            return result;
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return completion;
        }

        private void complete() {
            completion.complete(null);
        }
    }
}
