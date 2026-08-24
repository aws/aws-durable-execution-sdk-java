// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategy;

/**
 * A SerDes decorator that retries transient failures from another {@link SerDes}.
 *
 * <p>Only {@link RetryableSerDesException} is retried. Other failures are propagated immediately. Retry delays block
 * the calling thread, which is the dedicated SerDes executor thread for SDK-managed calls.
 */
public final class RetrySerDes implements SerDes {
    private static final Sleeper DEFAULT_SLEEPER = delay -> {
        if (delay.getSeconds() > 0) {
            TimeUnit.SECONDS.sleep(delay.getSeconds());
        }
        if (delay.getNano() > 0) {
            TimeUnit.NANOSECONDS.sleep(delay.getNano());
        }
    };

    private final SerDes delegate;
    private final RetryStrategy retryStrategy;
    private final Sleeper sleeper;

    /**
     * Creates a retrying SerDes decorator.
     *
     * @param delegate the SerDes to invoke
     * @param retryStrategy strategy that controls attempts and delays
     */
    public RetrySerDes(SerDes delegate, RetryStrategy retryStrategy) {
        this(delegate, retryStrategy, DEFAULT_SLEEPER);
    }

    RetrySerDes(SerDes delegate, RetryStrategy retryStrategy, Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.retryStrategy = Objects.requireNonNull(retryStrategy, "retryStrategy cannot be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper cannot be null");
    }

    @Override
    public String serialize(Object value) {
        return execute("serialization", () -> delegate.serialize(value));
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        return execute("deserialization", () -> delegate.deserialize(data, typeToken));
    }

    private <T> T execute(String action, Supplier<T> operation) {
        int attempt = 1;
        while (true) {
            try {
                return operation.get();
            } catch (RetryableSerDesException failure) {
                var decision = makeRetryDecision(action, failure, attempt);
                if (!decision.shouldRetry()) {
                    throw failure;
                }
                waitForRetry(action, failure, attempt, decision.delay());
                attempt++;
            }
        }
    }

    private RetryDecision makeRetryDecision(String action, RetryableSerDesException failure, int attempt) {
        try {
            var decision = retryStrategy.makeRetryDecision(failure, attempt);
            if (decision == null) {
                throw new SerDesException(
                        String.format("Retry strategy returned null for SerDes %s attempt %d", action, attempt));
            }
            return decision;
        } catch (SerDesException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SerDesException(
                    String.format("Retry strategy failed for SerDes %s attempt %d", action, attempt), e);
        }
    }

    private void waitForRetry(String action, RetryableSerDesException failure, int attempt, Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new SerDesException(String.format(
                    "Retry strategy returned an invalid delay for SerDes %s attempt %d", action, attempt));
        }
        if (delay.isZero()) {
            return;
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var interrupted = new SerDesException(
                    String.format("Interrupted while waiting to retry SerDes %s after attempt %d", action, attempt), e);
            interrupted.addSuppressed(failure);
            throw interrupted;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }
}
