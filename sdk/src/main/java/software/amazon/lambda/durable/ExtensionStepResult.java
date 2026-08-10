// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.time.Duration;
import java.util.Objects;

/**
 * Fixed outcomes supported by a stateful extension STEP primitive.
 *
 * @param <T> the checkpointed state and final result type
 */
public sealed interface ExtensionStepResult<T> permits ExtensionStepResult.Succeeded, ExtensionStepResult.Retry {

    /** Creates a terminal successful outcome. */
    static <T> Succeeded<T> succeed(T value) {
        return new Succeeded<>(value);
    }

    /** Creates a retry outcome with checkpointed state and delay. */
    static <T> Retry<T> retry(T state, Duration delay) {
        return new Retry<>(state, delay);
    }

    /** Terminal successful outcome. */
    record Succeeded<T>(T value) implements ExtensionStepResult<T> {}

    /** Retry outcome. */
    record Retry<T>(T state, Duration delay) implements ExtensionStepResult<T> {
        public Retry {
            Objects.requireNonNull(delay, "delay cannot be null");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay cannot be negative");
            }
        }
    }
}
