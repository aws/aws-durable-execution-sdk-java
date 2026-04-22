// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import java.time.Duration;
import java.util.Objects;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RetryOperationConfig;
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
 * var result = RetryOperationHelper.retryOperation(
 *     context,
 *     "approval",
 *     (ctx, attempt) -> ctx.waitForCallback(
 *         "approval-" + attempt,
 *         String.class,
 *         (callbackId, stepCtx) -> sendApprovalEmail(approverEmail, callbackId)
 *     ),
 *     RetryOperationConfig.builder()
 *         .retryStrategy(RetryStrategies.exponentialBackoff(
 *             3, Duration.ofSeconds(2), Duration.ofSeconds(30), 2.0, JitterStrategy.FULL))
 *         .build()
 * );
 * }</pre>
 *
 * <h2>Usage — invoke retry (anonymous form)</h2>
 *
 * <pre>{@code
 * var result = RetryOperationHelper.retryOperation(
 *     context,
 *     (ctx, attempt) -> ctx.invoke(
 *         "charge-" + attempt, paymentFnArn, new ChargeRequest(orderId), String.class),
 *     RetryOperationConfig.builder()
 *         .retryStrategy((err, att) -> att < 3
 *             ? RetryDecision.retry(Duration.ofSeconds(1))
 *             : RetryDecision.fail())
 *         .build()
 * );
 * }</pre>
 */
public final class RetryOperationHelper {

    private static final Duration DEFAULT_BACKOFF_DELAY = Duration.ofSeconds(1);
    private static final String BACKOFF_SUFFIX = "-backoff-";
    private static final String ANONYMOUS_BACKOFF_PREFIX = "retry-backoff-";

    private RetryOperationHelper() {
        // utility class
    }

    /**
     * Named form — wraps the retry loop in {@code runInChildContext} by default so all attempts are grouped under a
     * single named operation in execution history.
     *
     * <p>The child-context wrapping can be disabled via
     * {@link RetryOperationConfig.Builder#wrapInChildContext(boolean)}.
     *
     * @param <T> the result type
     * @param context the durable context
     * @param name operation name (used for child context and backoff wait names)
     * @param operation the retryable operation — receives the context and 1-based attempt number
     * @param config retry configuration including the retry strategy
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    public static <T> T retryOperation(
            DurableContext context, String name, RetryableOperation<T> operation, RetryOperationConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (config.wrapInChildContext()) {
            return (T) context.runInChildContext(
                    name, new TypeToken<Object>() {}, childCtx -> executeRetryLoop(childCtx, name, operation, config));
        }
        return executeRetryLoop(context, name, operation, config);
    }

    /**
     * Anonymous form — runs the retry loop directly in the caller's context. No child-context wrapping is applied
     * regardless of the {@code wrapInChildContext} config setting.
     *
     * @param <T> the result type
     * @param context the durable context
     * @param operation the retryable operation — receives the context and 1-based attempt number
     * @param config retry configuration including the retry strategy
     * @return the operation result
     */
    public static <T> T retryOperation(
            DurableContext context, RetryableOperation<T> operation, RetryOperationConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        return executeRetryLoop(context, null, operation, config);
    }

    /**
     * Core retry loop. Replay-safe because every side-effect is a durable operation: the user's operation calls durable
     * primitives, and backoff uses {@code context.wait()}.
     */
    private static <T> T executeRetryLoop(
            DurableContext context, String name, RetryableOperation<T> operation, RetryOperationConfig config) {
        var attempt = 1;
        while (true) {
            try {
                return operation.execute(context, attempt);
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
