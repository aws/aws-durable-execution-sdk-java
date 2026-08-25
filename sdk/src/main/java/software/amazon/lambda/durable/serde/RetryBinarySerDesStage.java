// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.Objects;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.retry.RetryStrategy;

/**
 * A binary-stage decorator that retries transient failures from another {@link BinarySerDesStage}.
 *
 * <p>Only {@link RetryableSerDesException} is retried. Other failures are propagated immediately. Retry delays block
 * the thread executing the SerDes call: the caller by default or the configured SerDes executor thread. Every attempt
 * receives the same {@link SerDesContext} supplied to this decorator.
 */
public final class RetryBinarySerDesStage implements BinarySerDesStage {
    private final BinarySerDesStage delegate;
    private final SerDesRetryExecutor retryExecutor;

    /**
     * Creates a retrying binary-stage decorator.
     *
     * @param delegate the stage to invoke
     * @param retryStrategy strategy that controls attempts and delays
     */
    public RetryBinarySerDesStage(BinarySerDesStage delegate, RetryStrategy retryStrategy) {
        this(delegate, retryStrategy, SerDesRetryExecutor.DEFAULT_SLEEPER);
    }

    RetryBinarySerDesStage(
            BinarySerDesStage delegate, RetryStrategy retryStrategy, SerDesRetryExecutor.Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        retryExecutor = new SerDesRetryExecutor(retryStrategy, sleeper);
    }

    @Override
    public byte[] serialize(byte[] value, SerDesContext context) {
        return retryExecutor.execute("binary stage serialization", () -> delegate.serialize(value, context));
    }

    @Override
    public byte[] deserialize(byte[] data, SerDesContext context) {
        return retryExecutor.execute("binary stage deserialization", () -> delegate.deserialize(data, context));
    }
}
