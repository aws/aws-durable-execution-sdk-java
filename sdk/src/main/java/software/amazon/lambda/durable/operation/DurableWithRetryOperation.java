// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.SafeCloseable;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.RetryStrategy;

/** Context-free static facade and canonical implementation of replay-safe retry operations. */
public final class DurableWithRetryOperation {
    private static final Duration DEFAULT_BACKOFF_DELAY = Duration.ofSeconds(1);
    private static final String BACKOFF_SUFFIX = "-backoff-";
    private static final String ANONYMOUS_CONTEXT_NAME = "retry";
    private static final String ANONYMOUS_BACKOFF_PREFIX = "retry-backoff-";
    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    private DurableWithRetryOperation() {}

    public static <T> T withRetry(String name, Supplier<T> operation) {
        return withRetryAsync(name, operation).get();
    }

    public static <T> T withRetry(String name, Supplier<T> operation, WithRetryConfig config) {
        return withRetryAsync(name, operation, config).get();
    }

    public static <T> DurableFuture<T> withRetryAsync(String name, Supplier<T> operation) {
        return withRetryAsync(name, operation, WithRetryConfig.builder().build());
    }

    public static <T> DurableFuture<T> withRetryAsync(String name, Supplier<T> operation, WithRetryConfig config) {
        return withRetryAsync(ExtensionContext.getCurrentContext(), name, adapt(operation), config);
    }

    @SuppressWarnings("unchecked")
    public static <T> DurableFuture<T> withRetryAsync(
            ExtensionContext context,
            String name,
            BiFunction<Integer, DurableContext, T> operation,
            WithRetryConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        var stage = withRetryStage(
                context,
                name,
                (attempt, durableContext) ->
                        CompletableFuture.completedFuture(operation.apply(attempt, durableContext)),
                config);
        return CompletionStageDurableFuture.from(context, stage);
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<T> withRetryStage(
            ExtensionContext context,
            String name,
            BiFunction<Integer, DurableContext, ? extends CompletionStage<T>> operation,
            WithRetryConfig config) {
        var contextName = name != null ? name : ANONYMOUS_CONTEXT_NAME;
        var future = context.reserve(contextName)
                .runInChildContextAsync(
                        OperationSubType.WITH_RETRY.getValue(),
                        new TypeToken<Object>() {},
                        () -> executeRetryLoop(name, operation, config, 1)
                                .thenApply(result -> ExtensionContextResult.replayChildrenAboveSize(
                                        result, null, LARGE_RESULT_THRESHOLD)),
                        ExtensionContextConfig.builder()
                                .isVirtual(!config.wrapInChildContext())
                                .build());
        return (CompletionStage<T>) (CompletionStage<?>) future;
    }

    private static <T> BiFunction<Integer, DurableContext, T> adapt(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation cannot be null");
        return (attempt, ignored) -> {
            try (var scope = WithRetryContext.attach(attempt)) {
                return operation.get();
            }
        };
    }

    private static <T> CompletionStage<T> executeRetryLoop(
            String name,
            BiFunction<Integer, DurableContext, ? extends CompletionStage<T>> operation,
            WithRetryConfig config,
            int attempt) {
        var durableContext = DurableContext.requireCurrentContext();
        var extensionContext = ExtensionContext.getCurrentContext();
        final CompletionStage<T> attemptStage;
        try {
            attemptStage = Objects.requireNonNull(
                    operation.apply(attempt, durableContext), "Retry operation stage cannot be null");
        } catch (Throwable throwable) {
            return handleRetryFailure(name, operation, config, attempt, extensionContext, throwable);
        }
        return attemptStage
                .handle(RetryAttemptResult<T>::new)
                .thenCompose(result -> result.throwable() == null
                        ? CompletableFuture.completedFuture(result.value())
                        : handleRetryFailure(name, operation, config, attempt, extensionContext, result.throwable()));
    }

    private static <T> CompletionStage<T> handleRetryFailure(
            String name,
            BiFunction<Integer, DurableContext, ? extends CompletionStage<T>> operation,
            WithRetryConfig config,
            int attempt,
            ExtensionContext extensionContext,
            Throwable throwable) {
        var cause = software.amazon.lambda.durable.util.ExceptionHelper.unwrapCompletableFuture(throwable);
        if (cause instanceof SuspendExecutionException || cause instanceof UnrecoverableDurableExecutionException) {
            return CompletableFuture.failedFuture(cause);
        }
        if (!(cause instanceof Exception exception)) {
            return CompletableFuture.failedFuture(cause);
        }
        var decision = config.retryStrategy().makeRetryDecision(exception, attempt);
        if (!decision.shouldRetry()) {
            return CompletableFuture.failedFuture(cause);
        }
        var delay = decision.delay().isZero() ? DEFAULT_BACKOFF_DELAY : decision.delay();
        return extensionContext
                .reserve(backoffName(name, attempt))
                .waitAsync(OperationSubType.WAIT.getValue(), delay)
                .thenCompose(ignored -> executeRetryLoop(name, operation, config, attempt + 1));
    }

    private record RetryAttemptResult<T>(T value, Throwable throwable) {}

    private static String backoffName(String name, int attempt) {
        return name != null ? name + BACKOFF_SUFFIX + attempt : ANONYMOUS_BACKOFF_PREFIX + attempt;
    }

    /** Metadata for the retry body active on the current SDK-managed thread. */
    public static final class WithRetryContext {
        private static final ThreadLocal<WithRetryContext> CURRENT = new ThreadLocal<>();

        private final int attempt;

        private WithRetryContext(int attempt) {
            this.attempt = attempt;
        }

        /** Returns the retry context attached to the current SDK-managed thread. */
        public static WithRetryContext getCurrentContext() {
            var context = CURRENT.get();
            if (context == null) {
                throw new IllegalStateException("WithRetryContext is not active on the current thread");
            }
            return context;
        }

        /** Returns the current one-based retry attempt. */
        public int getAttempt() {
            return attempt;
        }

        /** Attaches retry metadata for the duration of the returned scope. */
        public static SafeCloseable attach(int attempt) {
            var previous = CURRENT.get();
            CURRENT.set(new WithRetryContext(attempt));
            return () -> restore(previous);
        }

        private static void restore(WithRetryContext previous) {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Configuration for replay-safe retry operations. */
    public static final class WithRetryConfig {
        private final RetryStrategy retryStrategy;
        private final boolean wrapInChildContext;

        private WithRetryConfig(Builder builder) {
            retryStrategy = Objects.requireNonNullElse(builder.retryStrategy, RetryStrategies.Presets.DEFAULT);
            wrapInChildContext = builder.wrapInChildContext;
        }

        public RetryStrategy retryStrategy() {
            return retryStrategy;
        }

        public boolean wrapInChildContext() {
            return wrapInChildContext;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder().retryStrategy(retryStrategy).wrapInChildContext(wrapInChildContext);
        }

        /** Builder for {@link WithRetryConfig}. */
        public static final class Builder {
            private RetryStrategy retryStrategy;
            private boolean wrapInChildContext;

            private Builder() {}

            public Builder retryStrategy(RetryStrategy retryStrategy) {
                this.retryStrategy = retryStrategy;
                return this;
            }

            public Builder wrapInChildContext(boolean wrapInChildContext) {
                this.wrapInChildContext = wrapInChildContext;
                return this;
            }

            public WithRetryConfig build() {
                return new WithRetryConfig(this);
            }
        }
    }
}
