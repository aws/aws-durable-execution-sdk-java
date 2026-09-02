// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

import java.util.Objects;
import software.amazon.lambda.durable.retry.RetryStrategy;

/** A payload-offloader decorator that retries explicitly retryable storage failures. */
public final class RetryPayloadOffloader implements PayloadOffloader {
    private final PayloadOffloader delegate;
    private final PayloadOffloadRetryExecutor retryExecutor;

    public RetryPayloadOffloader(PayloadOffloader delegate, RetryStrategy retryStrategy) {
        this(delegate, retryStrategy, PayloadOffloadRetryExecutor.DEFAULT_SLEEPER);
    }

    RetryPayloadOffloader(
            PayloadOffloader delegate, RetryStrategy retryStrategy, PayloadOffloadRetryExecutor.Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        retryExecutor = new PayloadOffloadRetryExecutor(retryStrategy, sleeper);
    }

    @Override
    public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
        return retryExecutor.execute("store", () -> delegate.offload(serializedPayload, context));
    }

    @Override
    public String load(OffloadedPayload payload, PayloadOffloadContext context) {
        return retryExecutor.execute("load", () -> delegate.load(payload, context));
    }
}
