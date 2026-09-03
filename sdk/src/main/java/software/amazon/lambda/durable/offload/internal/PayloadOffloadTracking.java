// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.internal;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;

/**
 * Internal hook used by local testing utilities to retain the offloader selected for each persisted payload.
 *
 * <p>Applications should not use this class.
 */
public final class PayloadOffloadTracking {
    private static final ConcurrentHashMap<String, BiConsumer<PayloadOffloadContext, PayloadOffloader>> OBSERVERS =
            new ConcurrentHashMap<>();

    private PayloadOffloadTracking() {}

    /** Registers an observer for one local durable execution until the returned registration is closed. */
    public static Registration observe(
            String durableExecutionArn, BiConsumer<PayloadOffloadContext, PayloadOffloader> observer) {
        Objects.requireNonNull(durableExecutionArn, "durableExecutionArn cannot be null");
        Objects.requireNonNull(observer, "observer cannot be null");
        if (OBSERVERS.putIfAbsent(durableExecutionArn, observer) != null) {
            throw new IllegalStateException("Payload offload tracking is already active for " + durableExecutionArn);
        }
        return () -> OBSERVERS.remove(durableExecutionArn, observer);
    }

    /** Records the selected offloader when an observer is active for the payload's execution. */
    public static void record(PayloadOffloadContext context, PayloadOffloader offloader) {
        var observer = OBSERVERS.get(context.durableExecutionArn());
        if (observer != null) {
            observer.accept(context, offloader);
        }
    }

    /** Registration handle for an active observer. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
