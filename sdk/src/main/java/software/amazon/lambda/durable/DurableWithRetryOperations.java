// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.context.extension.WithRetryExtension;
import software.amazon.lambda.durable.extension.ExtensionContext;

/** Context-free static facades for replay-safe retry operations. */
public final class DurableWithRetryOperations {
    private DurableWithRetryOperations() {}

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
        return WithRetryExtension.execute(currentContext(), name, adapt(operation), config);
    }

    private static <T> BiFunction<Integer, DurableContext, T> adapt(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation cannot be null");
        return (attempt, ignored) -> {
            try (var scope = WithRetryContext.attach(attempt)) {
                return operation.get();
            }
        };
    }

    private static ExtensionContext currentContext() {
        return ExtensionContext.getCurrentContext();
    }
}
