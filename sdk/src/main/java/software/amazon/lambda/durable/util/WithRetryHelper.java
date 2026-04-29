// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import java.time.Duration;
import java.util.Objects;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.retry.RetryDecision;

/**
 * Replay-safe retry loop for any durable operation.
 *
 * <p>Provides the same retry-with-backoff pattern that {@code context.step()} has built in, but for operations that
 * cannot live inside a step ({@code waitForCallback}, {@code invoke}, {@code waitForCondition}, etc.).
 *
 * <p>Every side-effect in the loop is a durable operation, so the loop is replay-safe by construction. On replay,
 * completed operations return cached results instantly and the loop fast-forwards to the current attempt.
 *
 * <h2>Usage — callback retry</h2>
 *
 * <pre>{@code
 * var result = WithRetryHelper.withRetry(
 *     context,
 *     "approval",
 *     (ctx, attempt) -> ctx.waitForCallback(
 *         "approval-" + attempt,
 *         String.class,
 *         (callbackId, stepCtx) -> sendApprovalEmail(approverEmail, callbackId)
 *     ),
 *     WithRetryConfig.builder()
 *         .retryStrategy(RetryStrategies.exponentialBackoff(
 *             3, Duration.ofSeconds(2), Duration.ofSeconds(30), 2.0, JitterStrategy.FULL))
 *         .build()
 * );
 * }</pre>
 *
 * <h2>Usage — invoke retry (anonymous form)</h2>
 *
 * <pre>{@code
 * var result = WithRetryHelper.withRetry(
 *     context,
 *     (ctx, attempt) -> ctx.invoke(
 *         "charge-" + attempt, paymentFnArn, new ChargeRequest(orderId), String.class),
 *     WithRetryConfig.builder()
 *         .retryStrategy((err, att) -> att < 3
 *             ? RetryDecision.retry(Duration.ofSeconds(1))
 *             : RetryDecision.fail())
 *         .build()
 * );
 * }</pre>
 *
 * <h2>Usage — async form returning DurableFuture</h2>
 *
 * <pre>{@code
 * DurableFuture<String> future = WithRetryHelper.withRetryAsync(
 *     context,
 *     "approval",
 *     (ctx, attempt) -> ctx.waitForCallback(
 *         "approval-" + attempt, String.class,
 *         (callbackId, stepCtx) -> sendApprovalEmail(approverEmail, callbackId)
 *     ),
 *     WithRetryConfig.builder()
 *         .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(2)))
 *         .build()
 * );
 * // ... do other work ...
 * var result = future.get();
 * }</pre>
 */
public final class WithRetryHelper {

    private static final Duration DEFAULT_BACKOFF_DELAY = Duration.ofSeconds(1);
    private static final String BACKOFF_SUFFIX = "-backoff-";
    private static final String ANONYMOUS_BACKOFF_PREFIX = "retry-backoff-";

    private WithRetryHelper() {
        // utility class
    }

    /**
     * Named async form — wraps the retry loop in {@code runInChildContextAsync} by default so all attempts are grouped
     * under a single named operation in execution history, and returns a {@link DurableFuture} that can be composed or
     * blocked on.
     *
     * <p>The child-context wrapping can be disabled via {@link WithRetryConfig.Builder#wrapInChildContext(boolean)}.
     * When disabled, the retry loop executes immediately and the returned future is already completed.
     *
     * @param <T> the result type
     * @param context the durable context
     * @param name operation name (used for child context and backoff wait names)
     * @param operation the retryable operation — receives the context and 1-based attempt number
     * @param config retry configuration including the retry strategy
     * @return a future representing the operation result
     */
    @SuppressWarnings("unchecked")
    public static <T> DurableFuture<T> withRetryAsync(
            DurableContext context, String name, WithRetry<T> operation, WithRetryConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (config.wrapInChildContext()) {
            return (DurableFuture<T>) context.runInChildContextAsync(
                    name, new TypeToken<Object>() {}, childCtx -> executeRetryLoop(childCtx, name, operation, config));
        }
        return new CompletedDurableFuture<>(executeRetryLoop(context, name, operation, config));
    }

    /**
     * Named sync form — wraps the retry loop in {@code runInChildContext} by default so all attempts are grouped under
     * a single named operation in execution history, and blocks until the result is available.
     *
     * <p>Equivalent to {@code withRetryAsync(context, name, operation, config).get()}.
     *
     * @param <T> the result type
     * @param context the durable context
     * @param name operation name (used for child context and backoff wait names)
     * @param operation the retryable operation — receives the context and 1-based attempt number
     * @param config retry configuration including the retry strategy
     * @return the operation result
     */
    public static <T> T withRetry(DurableContext context, String name, WithRetry<T> operation, WithRetryConfig config) {
        return withRetryAsync(context, name, operation, config).get();
    }

    /**
     * Anonymous async form — runs the retry loop directly in the caller's context and returns a {@link DurableFuture}.
     * No child-context wrapping is applied regardless of the {@code wrapInChildContext} config setting.
     *
     * <p>Because the anonymous form executes the retry loop inline (no child context), the returned future is always
     * already completed.
     *
     * @param <T> the result type
     * @param context the durable context
     * @param operation the retryable operation — receives the context and 1-based attempt number
     * @param config retry configuration including the retry strategy
     * @return a future representing the operation result
     */
    public static <T> DurableFuture<T> withRetryAsync(
            DurableContext context, WithRetry<T> operation, WithRetryConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        return new CompletedDurableFuture<>(executeRetryLoop(context, null, operation, config));
    }

    /**
     * Anonymous sync form — runs the retry loop directly in the caller's context. No child-context wrapping is applied
     * regardless of the {@code wrapInChildContext} config setting.
     *
     * <p>Equivalent to {@code withRetryAsync(context, operation, config).get()}.
     *
     * @param <T> the result type
     * @param context the durable context
     * @param operation the retryable operation — receives the context and 1-based attempt number
     * @param config retry configuration including the retry strategy
     * @return the operation result
     */
    public static <T> T withRetry(DurableContext context, WithRetry<T> operation, WithRetryConfig config) {
        return withRetryAsync(context, operation, config).get();
    }

    /**
     * Core retry loop. Replay-safe because every side-effect is a durable operation: the user's operation calls durable
     * primitives, and backoff uses {@code context.wait()}.
     *
     * <p>{@link SuspendExecutionException} and {@link UnrecoverableDurableExecutionException} are never retried — they
     * are internal SDK control flow signals that must propagate immediately.
     */
    private static <T> T executeRetryLoop(
            DurableContext context, String name, WithRetry<T> operation, WithRetryConfig config) {
        var attempt = 1;
        while (true) {
            try {
                return operation.execute(context, attempt);
            } catch (SuspendExecutionException | UnrecoverableDurableExecutionException e) {
                // Internal SDK control flow — never retry, always propagate
                throw e;
            } catch (Exception e) {
                RetryDecision decision = config.retryStrategy().makeRetryDecision(e, attempt);
                if (!decision.shouldRetry()) {
                    throw e;
                }

                var delay = decision.delay().isZero() ? DEFAULT_BACKOFF_DELAY : decision.delay();
                var waitName = name != null ? name + BACKOFF_SUFFIX + attempt : ANONYMOUS_BACKOFF_PREFIX + attempt;
                context.wait(waitName, delay);
                attempt++;
            }
        }
    }
}
