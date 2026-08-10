// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context.extension;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.model.OperationSubType;

/** Canonical implementation of the built-in with-retry extension. */
public final class WithRetryExtension {
    private static final Duration DEFAULT_BACKOFF_DELAY = Duration.ofSeconds(1);
    private static final String BACKOFF_SUFFIX = "-backoff-";
    private static final String ANONYMOUS_CONTEXT_NAME = "retry";
    private static final String ANONYMOUS_BACKOFF_PREFIX = "retry-backoff-";

    private WithRetryExtension() {}

    @SuppressWarnings("unchecked")
    public static <T> DurableFuture<T> execute(
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
                extensionContext.reserve(backoffName(name, attempt)).wait(delay);
                attempt++;
            }
        }
    }

    private static String backoffName(String name, int attempt) {
        return name != null ? name + BACKOFF_SUFFIX + attempt : ANONYMOUS_BACKOFF_PREFIX + attempt;
    }
}
