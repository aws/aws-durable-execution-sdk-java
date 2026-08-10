// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.WithRetryContext;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.RetryStrategy;

/** Context-free static facade and canonical implementation of replay-safe retry operations. */
public final class DurableWithRetryOperation {
    private static final Duration DEFAULT_BACKOFF_DELAY = Duration.ofSeconds(1);
    private static final String BACKOFF_SUFFIX = "-backoff-";
    private static final String ANONYMOUS_CONTEXT_NAME = "retry";
    private static final String ANONYMOUS_BACKOFF_PREFIX = "retry-backoff-";

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

        var contextName = name != null ? name : ANONYMOUS_CONTEXT_NAME;
        var future = context.reserve(contextName)
                .runInChildContextAsync(
                        OperationSubType.WITH_RETRY.getValue(),
                        new TypeToken<Object>() {},
                        () -> ExtensionContextResult.completed(executeRetryLoop(name, operation, config)),
                        ExtensionContextConfig.builder()
                                .childContextConfig(RunInChildContextConfig.builder()
                                        .isVirtual(!config.wrapInChildContext())
                                        .build())
                                .build());
        return (DurableFuture<T>) future;
    }

    private static <T> BiFunction<Integer, DurableContext, T> adapt(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation cannot be null");
        return (attempt, ignored) -> {
            try (var scope = WithRetryContext.attach(attempt)) {
                return operation.get();
            }
        };
    }

    private static <T> T executeRetryLoop(
            String name, BiFunction<Integer, DurableContext, T> operation, WithRetryConfig config) {
        var durableContext = DurableContext.getCurrentContext();
        var extensionContext = ExtensionContext.getCurrentContext();
        var attempt = 1;
        while (true) {
            try {
                return operation.apply(attempt, durableContext);
            } catch (SuspendExecutionException | UnrecoverableDurableExecutionException e) {
                throw e;
            } catch (Exception e) {
                var decision = config.retryStrategy().makeRetryDecision(e, attempt);
                if (!decision.shouldRetry()) {
                    throw e;
                }
                var delay = decision.delay().isZero() ? DEFAULT_BACKOFF_DELAY : decision.delay();
                extensionContext
                        .reserve(backoffName(name, attempt))
                        .waitAsync(OperationSubType.WAIT.getValue(), delay)
                        .get();
                attempt++;
            }
        }
    }

    private static String backoffName(String name, int attempt) {
        return name != null ? name + BACKOFF_SUFFIX + attempt : ANONYMOUS_BACKOFF_PREFIX + attempt;
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
