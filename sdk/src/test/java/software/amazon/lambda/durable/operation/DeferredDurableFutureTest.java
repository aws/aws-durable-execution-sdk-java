// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

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

        deferred.bind(CompletableFuture.completedFuture("result"));

        assertEquals("result", result.join());
    }

    @Test
    void completionSignalObtainedBeforeBindingTracksDelegate() {
        var deferred = new DurableConcurrencyOperation.DeferredDurableFuture<String>();
        var completion = deferred.completionFuture();
        var delegate = new CompletableFuture<String>();

        deferred.bind(delegate);
        assertFalse(completion.isDone());

        delegate.complete("result");

        completion.join();
    }

    @Test
    void bindRejectsASecondDelegate() {
        var deferred = new DurableConcurrencyOperation.DeferredDurableFuture<String>();
        deferred.bind(CompletableFuture.completedFuture("first"));

        var exception = assertThrows(
                IllegalStateException.class, () -> deferred.bind(CompletableFuture.completedFuture("second")));

        assertEquals("A deferred stage can only be bound once", exception.getMessage());
    }
}
