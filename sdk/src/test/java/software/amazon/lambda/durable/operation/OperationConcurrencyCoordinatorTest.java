// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.lambda.durable.model.ConcurrencyCompletionStatus.ALL_COMPLETED;
import static software.amazon.lambda.durable.model.ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED;
import static software.amazon.lambda.durable.operation.DurableConcurrencyOperation.OperationConcurrencyCoordinator.ItemStatus.FAILED;
import static software.amazon.lambda.durable.operation.DurableConcurrencyOperation.OperationConcurrencyCoordinator.ItemStatus.SKIPPED;
import static software.amazon.lambda.durable.operation.DurableConcurrencyOperation.OperationConcurrencyCoordinator.ItemStatus.SUCCEEDED;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.operation.DurableConcurrencyOperation.CompletionConfig;
import software.amazon.lambda.durable.operation.DurableConcurrencyOperation.OperationConcurrencyCoordinator;

class OperationConcurrencyCoordinatorTest {
    @Test
    void launchesNoMoreThanMaxConcurrency() throws Exception {
        var coordinator = new OperationConcurrencyCoordinator(2, CompletionConfig.allCompleted());
        var first = new TestFuture<>("first");
        var second = new TestFuture<>("second");
        var third = new TestFuture<>("third");
        var launched = new AtomicInteger();
        var firstTwoLaunched = new CountDownLatch(2);
        var thirdLaunched = new CountDownLatch(1);
        coordinator.register(() -> launch(first, launched, firstTwoLaunched));
        coordinator.register(() -> launch(second, launched, firstTwoLaunched));
        coordinator.register(() -> launch(third, launched, thirdLaunched));
        coordinator.closeRegistration();

        var result = CompletableFuture.supplyAsync(coordinator::awaitCompletion);
        firstTwoLaunched.await();

        assertEquals(2, launched.get());

        first.complete();
        thirdLaunched.await();
        second.complete();
        third.complete();

        assertEquals(ALL_COMPLETED, result.join().completionDecision().completionStatus());
    }

    @Test
    void launchesNextItemAfterSynchronousReplayCompletion() throws Exception {
        var coordinator = new OperationConcurrencyCoordinator(1, CompletionConfig.allCompleted());
        var replayed = new TestFuture<>("replayed");
        replayed.complete();
        var next = new TestFuture<>("next");
        var nextLaunched = new CountDownLatch(1);
        coordinator.register(() -> replayed);
        coordinator.register(() -> {
            nextLaunched.countDown();
            return next;
        });

        var result = CompletableFuture.supplyAsync(coordinator::awaitCompletion);
        var launchedWithoutExternalSignal = nextLaunched.await(200, TimeUnit.MILLISECONDS);
        coordinator.closeRegistration();
        next.complete();
        result.join();

        assertTrue(launchedWithoutExternalSignal);
    }

    @Test
    void earlyCompletionMarksUnlaunchedItemsSkipped() {
        var coordinator = new OperationConcurrencyCoordinator(1, CompletionConfig.minSuccessful(1));
        var first = new TestFuture<>("first");
        var launched = new AtomicInteger();
        coordinator.register(() -> {
            launched.incrementAndGet();
            return first;
        });
        coordinator.register(() -> {
            launched.incrementAndGet();
            return new TestFuture<>("second");
        });
        coordinator.register(() -> {
            launched.incrementAndGet();
            return new TestFuture<>("third");
        });
        coordinator.closeRegistration();

        var result = CompletableFuture.supplyAsync(coordinator::awaitCompletion);
        first.complete();
        var completion = result.join();

        assertEquals(MIN_SUCCESSFUL_REACHED, completion.completionDecision().completionStatus());
        assertEquals(1, launched.get());
        assertEquals(
                List.of(SUCCEEDED, SKIPPED, SKIPPED),
                completion.items().stream()
                        .map(OperationConcurrencyCoordinator.Item::status)
                        .toList());
    }

    @Test
    void failedItemsContributeToCompletionStatus() {
        var coordinator = new OperationConcurrencyCoordinator(2, CompletionConfig.allCompleted());
        var failed = new TestFuture<String>(new IllegalStateException("failed"));
        var succeeded = new TestFuture<>("succeeded");
        coordinator.register(() -> failed);
        coordinator.register(() -> succeeded);
        coordinator.closeRegistration();

        var result = CompletableFuture.supplyAsync(coordinator::awaitCompletion);
        failed.complete();
        succeeded.complete();
        var completion = result.join();

        assertEquals(ALL_COMPLETED, completion.completionDecision().completionStatus());
        assertEquals(
                List.of(FAILED, SUCCEEDED),
                completion.items().stream()
                        .map(OperationConcurrencyCoordinator.Item::status)
                        .toList());
    }

    @Test
    void dynamicRegistrationDoesNotCompleteUntilRegistrationCloses() throws Exception {
        var completedBeforeClose = new CountDownLatch(1);
        var completionConfig = CompletionConfig.shouldComplete(status -> {
            if (status.completedCount() == 1 && !status.allItemsRegistered()) {
                completedBeforeClose.countDown();
            }
            return status.allCompleted()
                    ? CompletionConfig.CompletionDecision.complete(ALL_COMPLETED)
                    : CompletionConfig.CompletionDecision.continueExecution();
        });
        var coordinator = new OperationConcurrencyCoordinator(1, completionConfig);
        var result = CompletableFuture.supplyAsync(coordinator::awaitCompletion);
        var item = new TestFuture<>("result");

        coordinator.register(() -> item);
        item.complete();
        completedBeforeClose.await();

        assertFalse(result.isDone());

        coordinator.closeRegistration();

        assertEquals(ALL_COMPLETED, result.join().completionDecision().completionStatus());
    }

    @Test
    void registrationAfterCloseFails() {
        var coordinator = new OperationConcurrencyCoordinator(1, CompletionConfig.allCompleted());
        coordinator.closeRegistration();

        var exception =
                assertThrows(IllegalStateException.class, () -> coordinator.register(() -> new TestFuture<>("late")));

        assertEquals("Cannot register items after registration is closed", exception.getMessage());
    }

    @Test
    void throwingCompletionCallbackBecomesUnrecoverable() {
        var coordinator = new OperationConcurrencyCoordinator(1, CompletionConfig.shouldComplete(status -> {
            throw new IllegalStateException("completion failed");
        }));
        coordinator.closeRegistration();

        var exception = assertThrows(IllegalDurableOperationException.class, coordinator::awaitCompletion);

        assertTrue(exception.getMessage().contains("completion failed"));
    }

    private static <T> DurableFuture<T> launch(
            TestFuture<T> future, AtomicInteger launched, CountDownLatch launchLatch) {
        launched.incrementAndGet();
        launchLatch.countDown();
        return future;
    }

    private static final class TestFuture<T> implements DurableFuture<T> {
        private final T result;
        private final RuntimeException failure;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private TestFuture(T result) {
            this.result = result;
            this.failure = null;
        }

        private TestFuture(RuntimeException failure) {
            this.result = null;
            this.failure = failure;
        }

        @Override
        public T get() {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return completion.thenApply(ignored -> null);
        }

        private void complete() {
            completion.complete(null);
        }
    }
}
