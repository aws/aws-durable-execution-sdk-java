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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.context.BaseContext;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextErrorHandler;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

/** Shared implementation and configuration types for durable concurrent operations. */
public abstract class DurableConcurrencyOperation {
    protected static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    DurableConcurrencyOperation() {}

    protected static ExtensionContextConfig childContextConfig(
            SerDes serDes, NestingType nestingType, ExtensionContextErrorHandler errorHandler) {
        return ExtensionContextConfig.builder()
                .serDes(serDes)
                .isVirtual(nestingType == NestingType.FLAT)
                .errorHandler(errorHandler)
                .build();
    }

    protected static ExtensionContextConfig parentContextConfig(SerDes serDes) {
        return parentContextConfig(serDes, false);
    }

    protected static ExtensionContextConfig parentContextConfig(SerDes serDes, boolean isVirtual) {
        return ExtensionContextConfig.builder()
                .serDes(serDes)
                .isVirtual(isVirtual)
                .emitUserFunctionEvents(false)
                .suppressLateChildCheckpoints(true)
                .build();
    }

    /** Controls when a concurrent operation completes. */
    public record CompletionConfig(
            Integer minSuccessful,
            Integer toleratedFailureCount,
            Double toleratedFailurePercentage,
            Function<CompletionStatus, CompletionDecision> shouldComplete) {

        public CompletionConfig(
                Integer minSuccessful, Integer toleratedFailureCount, Double toleratedFailurePercentage) {
            this(minSuccessful, toleratedFailureCount, toleratedFailurePercentage, null);
        }

        public CompletionConfig {
            if (shouldComplete != null
                    && (minSuccessful != null || toleratedFailureCount != null || toleratedFailurePercentage != null)) {
                throw new IllegalArgumentException(
                        "shouldComplete is mutually exclusive with minSuccessful, toleratedFailureCount, and toleratedFailurePercentage");
            }
        }

        /** Live completion progress for a concurrent operation. */
        public record CompletionStatus(
                int successCount, int failureCount, int completedCount, int totalCount, boolean allItemsRegistered) {
            public CompletionStatus(int successCount, int failureCount, int completedCount, int totalCount) {
                this(successCount, failureCount, completedCount, totalCount, completedCount == totalCount);
            }

            public CompletionStatus {
                if (successCount < 0 || failureCount < 0 || completedCount < 0 || totalCount < 0) {
                    throw new IllegalArgumentException("completion counts must be non-negative");
                }
                if (completedCount != successCount + failureCount) {
                    throw new IllegalArgumentException("completedCount must equal successCount + failureCount");
                }
                if (completedCount > totalCount) {
                    throw new IllegalArgumentException("completedCount cannot exceed totalCount");
                }
            }

            public boolean allCompleted() {
                return allItemsRegistered && completedCount == totalCount;
            }
        }

        /** The completion decision returned by {@link #completionDecisionFunction()}. */
        public record CompletionDecision(boolean shouldComplete, ConcurrencyCompletionStatus completionStatus) {
            public CompletionDecision {
                if (shouldComplete && completionStatus == null) {
                    throw new IllegalArgumentException("completionStatus is required when shouldComplete is true");
                }
                if (!shouldComplete && completionStatus != null) {
                    throw new IllegalArgumentException("completionStatus must be null when shouldComplete is false");
                }
            }

            public static CompletionDecision complete(ConcurrencyCompletionStatus completionStatus) {
                return new CompletionDecision(true, completionStatus);
            }

            public static CompletionDecision continueExecution() {
                return new CompletionDecision(false, null);
            }

            public boolean isSucceeded() {
                return shouldComplete && completionStatus.isSucceeded();
            }
        }

        /** All items must succeed. Zero failures are tolerated. */
        public static CompletionConfig allSuccessful() {
            return new CompletionConfig(null, 0, null);
        }

        /** All items run regardless of failures. */
        public static CompletionConfig allCompleted() {
            return new CompletionConfig(null, null, null);
        }

        /** Complete as soon as the first item succeeds. */
        public static CompletionConfig firstSuccessful() {
            return new CompletionConfig(1, null, null);
        }

        /** Complete when the specified number of items have succeeded. */
        public static CompletionConfig minSuccessful(int count) {
            if (count < 1) {
                throw new IllegalArgumentException("minSuccessful must be at least 1, got: " + count);
            }
            return new CompletionConfig(count, null, null);
        }

        /** Complete when more than the specified number of failures have occurred. */
        public static CompletionConfig toleratedFailureCount(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("toleratedFailureCount must be non-negative, got: " + count);
            }
            return new CompletionConfig(null, count, null);
        }

        /** Complete when the failure percentage exceeds the specified threshold. */
        public static CompletionConfig toleratedFailurePercentage(double percentage) {
            if (percentage < 0.0 || percentage > 1.0) {
                throw new IllegalArgumentException(
                        "toleratedFailurePercentage must be between 0.0 and 1.0, got: " + percentage);
            }
            return new CompletionConfig(null, null, percentage);
        }

        /** Complete when the function returns a completing decision. */
        public static CompletionConfig shouldComplete(Function<CompletionStatus, CompletionDecision> shouldComplete) {
            Objects.requireNonNull(shouldComplete, "shouldComplete cannot be null");
            return new CompletionConfig(null, null, null, shouldComplete);
        }

        /** Returns the configured completion decision function. */
        public Function<CompletionStatus, CompletionDecision> completionDecisionFunction() {
            return shouldComplete != null ? shouldComplete : thresholdBasedShouldComplete();
        }

        public boolean hasCustomShouldComplete() {
            return shouldComplete != null;
        }

        private Function<CompletionStatus, CompletionDecision> thresholdBasedShouldComplete() {
            return status -> {
                if (minSuccessful != null) {
                    if (status.successCount() >= minSuccessful) {
                        return CompletionDecision.complete(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED);
                    }
                    if (status.allItemsRegistered() && status.totalCount() < minSuccessful) {
                        throw new IllegalStateException("minSuccessful (" + minSuccessful
                                + ") exceeds the number of registered items (" + status.totalCount() + ")");
                    }
                }

                var toleratedFailures = toleratedFailureLimit(status.totalCount());
                if (toleratedFailures != null && status.failureCount() > toleratedFailures) {
                    return CompletionDecision.complete(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED);
                }

                if (status.allCompleted()) {
                    return CompletionDecision.complete(ConcurrencyCompletionStatus.ALL_COMPLETED);
                }

                return CompletionDecision.continueExecution();
            };
        }

        private Integer toleratedFailureLimit(int totalCount) {
            if (toleratedFailureCount == null && toleratedFailurePercentage == null) {
                return null;
            }
            var count = toleratedFailureCount != null ? toleratedFailureCount : Integer.MAX_VALUE;
            var percentageCount = toleratedFailurePercentage != null
                    ? (int) Math.floor(totalCount * toleratedFailurePercentage)
                    : Integer.MAX_VALUE;
            return Math.min(count, percentageCount);
        }
    }

    /** Controls whether each item is represented by a checkpointed child context. */
    public enum NestingType {
        NESTED,
        FLAT
    }

    protected static final class OperationConcurrencyCoordinator {
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
            try {
                return awaitCompletionLoop(expectedCompletionStatus);
            } catch (Throwable exception) {
                var cause = ExceptionHelper.unwrapCompletableFuture(exception);
                if (cause instanceof SuspendExecutionException suspendExecutionException) {
                    throw suspendExecutionException;
                }
                if (cause instanceof UnrecoverableDurableExecutionException unrecoverableException) {
                    throw unrecoverableException;
                }
                throw new IllegalDurableOperationException("Unexpected exception in concurrency operation: " + cause);
            }
        }

        private Completion awaitCompletionLoop(ExpectedCompletionStatus expectedCompletionStatus) {
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
            var completed =
                    running.stream().filter(item -> item.future.isDone()).toList();
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

        private CompletionConfig.CompletionDecision completionDecision(
                ExpectedCompletionStatus expectedCompletionStatus) {
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

    protected static final class DeferredDurableFuture<T> implements DurableFuture<T> {
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
}
