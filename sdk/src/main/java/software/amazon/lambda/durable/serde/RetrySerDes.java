// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.Objects;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.retry.RetryStrategy;

/**
 * A SerDes decorator that retries transient failures from another {@link SerDes}.
 *
 * <p>Only {@link RetryableSerDesException} is retried. Other failures are propagated immediately. Retry delays block
 * the thread executing the SerDes call: the caller by default or the configured SerDes executor thread.
 */
public final class RetrySerDes implements SerDes {
    private final SerDes delegate;
    private final SerDesRetryExecutor retryExecutor;

    /**
     * Creates a retrying SerDes decorator.
     *
     * @param delegate the SerDes to invoke
     * @param retryStrategy strategy that controls attempts and delays
     */
    public RetrySerDes(SerDes delegate, RetryStrategy retryStrategy) {
        this(delegate, retryStrategy, SerDesRetryExecutor.DEFAULT_SLEEPER);
    }

    RetrySerDes(SerDes delegate, RetryStrategy retryStrategy, SerDesRetryExecutor.Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        retryExecutor = new SerDesRetryExecutor(retryStrategy, sleeper);
    }

    @Override
    public String serialize(Object value) {
        return retryExecutor.execute("serialization", () -> delegate.serialize(value));
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        return retryExecutor.execute("deserialization", () -> delegate.deserialize(data, typeToken));
    }
}
