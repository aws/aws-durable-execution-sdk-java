// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;

final class OperationConcurrencyCoordinator {
    enum ItemStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    record ExpectedCompletionStatus(int completed, CompletionConfig.CompletionDecision completionDecision) {
        ExpectedCompletionStatus {
            if (completed < 0) {
                throw new IllegalArgumentException("completed cannot be negative");
            }
            Objects.requireNonNull(completionDecision, "completionDecision cannot be null");
        }
    }

    record Completion(CompletionConfig.CompletionDecision completionDecision, List<Item<?>> items) {
        Completion {
            Objects.requireNonNull(completionDecision, "completionDecision cannot be null");
            items = List.copyOf(items);
        }
    }

    static final class Item<T> {
        private final Supplier<DurableFuture<T>> launcher;
        private final DeferredDurableFuture<T> future = new DeferredDurableFuture<>();
        private volatile ItemStatus status;

        private Item(Supplier<DurableFuture<T>> launcher, ItemStatus status) {
            this.launcher = launcher;
            this.status = status;
        }

        DurableFuture<T> future() {
            return future;
        }

        ItemStatus status() {
            return status;
        }
    }

    private final Object lock = new Object();
    private final int maxConcurrency;
    private final Function<CompletionConfig.CompletionStatus, CompletionConfig.CompletionDecision> shouldComplete;
    private final List<Item<?>> items = new ArrayList<>();
    private final Queue<Item<?>> pending = new ArrayDeque<>();
    private final Set<Item<?>> running = new LinkedHashSet<>();
    private CompletableFuture<Void> changed = new CompletableFuture<>();
    private boolean registrationClosed;
    private int succeeded;
    private int failed;

    OperationConcurrencyCoordinator(int maxConcurrency, CompletionConfig completionConfig) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        this.maxConcurrency = maxConcurrency;
        this.shouldComplete = Objects.requireNonNull(completionConfig, "completionConfig cannot be null")
                .completionDecisionFunction();
    }

    <T> Item<T> register(Supplier<DurableFuture<T>> launcher) {
        return register(launcher, false);
    }

    <T> Item<T> register(Supplier<DurableFuture<T>> launcher, boolean skipped) {
        Objects.requireNonNull(launcher, "launcher cannot be null");
        synchronized (lock) {
            if (registrationClosed) {
                throw new IllegalStateException("Cannot register items after registration is closed");
            }
            var item = new Item<>(launcher, skipped ? ItemStatus.SKIPPED : ItemStatus.PENDING);
            items.add(item);
            if (!skipped) {
                pending.add(item);
            }
            notifyChanged();
            return item;
        }
    }

    void closeRegistration() {
        synchronized (lock) {
            registrationClosed = true;
            notifyChanged();
        }
    }

    Completion awaitCompletion() {
        return awaitCompletion(null);
    }

    Completion awaitCompletion(ExpectedCompletionStatus expectedCompletionStatus) {
        while (true) {
            DurableFuture<?>[] waiters;
            synchronized (lock) {
                collectCompletedItems();
                var decision = completionDecision(expectedCompletionStatus);
                if (decision != null) {
                    markIncompleteItemsSkipped();
                    return new Completion(decision, items);
                }

                launchPendingItems();
                collectCompletedItems();
                decision = completionDecision(expectedCompletionStatus);
                if (decision != null) {
                    markIncompleteItemsSkipped();
                    return new Completion(decision, items);
                }
                if (running.size() < maxConcurrency && !pending.isEmpty()) {
                    continue;
                }
                waiters = completionWaiters();
            }
            DurableFuture.anyOf(waiters);
        }
    }

    private void launchPendingItems() {
        while (running.size() < maxConcurrency && !pending.isEmpty()) {
            var item = pending.remove();
            launch(item);
            running.add(item);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void launch(Item<?> untypedItem) {
        var item = (Item<T>) untypedItem;
        var delegate = Objects.requireNonNull(item.launcher.get(), "launcher cannot return null");
        item.future.bind(delegate);
        item.status = ItemStatus.RUNNING;
    }

    private void collectCompletedItems() {
        var completed = running.stream().filter(item -> item.future.isDone()).toList();
        for (var item : completed) {
            running.remove(item);
            complete(item);
        }
    }

    private void complete(Item<?> item) {
        try {
            item.future.get();
            item.status = ItemStatus.SUCCEEDED;
            succeeded++;
        } catch (SuspendExecutionException | UnrecoverableDurableExecutionException exception) {
            throw exception;
        } catch (Throwable throwable) {
            item.status = ItemStatus.FAILED;
            failed++;
        }
    }

    private CompletionConfig.CompletionDecision completionDecision(ExpectedCompletionStatus expectedCompletionStatus) {
        if (expectedCompletionStatus != null) {
            return succeeded + failed >= expectedCompletionStatus.completed()
                    ? expectedCompletionStatus.completionDecision()
                    : null;
        }
        var status = new CompletionConfig.CompletionStatus(
                succeeded, failed, succeeded + failed, items.size(), registrationClosed);
        var decision = Objects.requireNonNull(
                shouldComplete.apply(status), "shouldComplete must return a completion decision");
        return decision.shouldComplete() ? decision : null;
    }

    private DurableFuture<?>[] completionWaiters() {
        if (changed.isDone()) {
            changed = new CompletableFuture<>();
        }
        var waiters = new ArrayList<DurableFuture<?>>();
        running.stream().map(item -> new CompletionOnlyFuture(item.future)).forEach(waiters::add);
        waiters.add(new SignalFuture(changed));
        return waiters.toArray(DurableFuture[]::new);
    }

    private void markIncompleteItemsSkipped() {
        items.stream()
                .filter(item -> item.status == ItemStatus.PENDING || item.status == ItemStatus.RUNNING)
                .forEach(item -> item.status = ItemStatus.SKIPPED);
        pending.clear();
        running.clear();
    }

    private void notifyChanged() {
        changed.complete(null);
    }

    private record CompletionOnlyFuture(DurableFuture<?> delegate) implements DurableFuture<Void> {
        @Override
        public Void get() {
            return null;
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return delegate.completionFuture();
        }
    }

    private record SignalFuture(CompletableFuture<Void> signal) implements DurableFuture<Void> {
        @Override
        public Void get() {
            signal.join();
            return null;
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return signal.thenApply(ignored -> null);
        }
    }
}
