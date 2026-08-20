// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import software.amazon.lambda.durable.util.ParameterValidator;

/**
 * Fixed outcomes supported by a stateful extension STEP primitive.
 *
 * @param <T> the checkpointed state and final result type
 */
public sealed interface ExtensionStepResult<T>
        permits ExtensionStepResult.Succeeded, ExtensionStepResult.Retry, ExtensionStepResult.RetryAfterNormalization {

    /** Creates a terminal successful outcome. */
    static <T> Succeeded<T> succeed(T value) {
        return new Succeeded<>(value);
    }

    /** Creates a retry outcome with checkpointed state and delay. */
    static <T> Retry<T> retry(T state, Duration delay) {
        return new Retry<>(state, delay);
    }

    /** Creates a retry whose delay is evaluated from the checkpoint-normalized state. */
    static <T> RetryAfterNormalization<T> retryAfterNormalization(T state, Function<T, Duration> delayStrategy) {
        return new RetryAfterNormalization<>(state, delayStrategy);
    }

    /** Creates a decision that a failed attempt should not be retried. */
    static <T> DoNotRetry<T> doNotRetry() {
        return new DoNotRetry<>();
    }

    /** Outcomes supported when deciding whether to retry a failed attempt. */
    sealed interface RetryDecision<T> permits Retry, DoNotRetry {}

    /** Terminal successful outcome. */
    record Succeeded<T>(T value) implements ExtensionStepResult<T> {}

    /** Retry outcome. */
    record Retry<T>(T state, Duration delay) implements ExtensionStepResult<T>, RetryDecision<T> {
        public Retry {
            validateDelay(delay);
        }
    }

    /** Retry outcome whose delay is evaluated after the state completes its SerDes round trip. */
    record RetryAfterNormalization<T>(T state, Function<T, Duration> delayStrategy) implements ExtensionStepResult<T> {
        public RetryAfterNormalization {
            Objects.requireNonNull(delayStrategy, "delayStrategy cannot be null");
        }

        public Duration delay(T normalizedState) {
            return validateDelay(delayStrategy.apply(normalizedState));
        }
    }

    /** Decision that a failed attempt should not be retried. */
    record DoNotRetry<T>() implements RetryDecision<T> {}

    private static Duration validateDelay(Duration delay) {
        Objects.requireNonNull(delay, "delay cannot be null");
        ParameterValidator.validateDuration(delay, "delay");
        return delay;
    }
}
