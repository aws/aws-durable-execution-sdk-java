// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.RetryablePayloadOffloadException;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategy;

final class PayloadOffloadRetryExecutor {
    static final Sleeper DEFAULT_SLEEPER = delay -> {
        if (delay.getSeconds() > 0) {
            TimeUnit.SECONDS.sleep(delay.getSeconds());
        }
        if (delay.getNano() > 0) {
            TimeUnit.NANOSECONDS.sleep(delay.getNano());
        }
    };

    private final RetryStrategy retryStrategy;
    private final Sleeper sleeper;

    PayloadOffloadRetryExecutor(RetryStrategy retryStrategy, Sleeper sleeper) {
        this.retryStrategy = Objects.requireNonNull(retryStrategy, "retryStrategy cannot be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper cannot be null");
    }

    <T> T execute(String action, Supplier<T> operation) {
        int attempt = 1;
        while (true) {
            try {
                return operation.get();
            } catch (RetryablePayloadOffloadException failure) {
                var decision = makeRetryDecision(action, failure, attempt);
                if (!decision.shouldRetry()) {
                    throw failure;
                }
                waitForRetry(action, failure, attempt, decision.delay());
                attempt++;
            }
        }
    }

    private RetryDecision makeRetryDecision(String action, RetryablePayloadOffloadException failure, int attempt) {
        try {
            var decision = retryStrategy.makeRetryDecision(failure, attempt);
            if (decision == null) {
                throw new PayloadOffloadException(
                        String.format("Retry strategy returned null for payload %s attempt %d", action, attempt));
            }
            return decision;
        } catch (PayloadOffloadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PayloadOffloadException(
                    String.format("Retry strategy failed for payload %s attempt %d", action, attempt), e);
        }
    }

    private void waitForRetry(String action, RetryablePayloadOffloadException failure, int attempt, Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new PayloadOffloadException(String.format(
                    "Retry strategy returned an invalid delay for payload %s attempt %d", action, attempt));
        }
        if (delay.isZero()) {
            return;
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var interrupted = new RetryablePayloadOffloadException(
                    String.format("Interrupted while waiting to retry payload %s after attempt %d", action, attempt),
                    e);
            interrupted.addSuppressed(failure);
            throw interrupted;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }
}
