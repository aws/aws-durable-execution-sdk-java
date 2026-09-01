// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.model.OperationSubType.STEP;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.util.ParameterValidator;

/**
 * Replay-safe helpers for common nondeterministic values.
 *
 * <p>Each helper checkpoints its generated value as a durable STEP. Replays return the checkpointed value without
 * generating a new UUID, timestamp, or random number.
 */
public final class DurableReplaySafeValueOperation {
    private static final String UUID_NAME = "uuid";
    private static final String UUID_SUBTYPE = "UUID";
    private static final String NOW_NAME = "now";
    private static final String NOW_SUBTYPE = "Now";
    private static final String RANDOM_NAME = "random";
    private static final String RANDOM_SUBTYPE = "Random";

    private DurableReplaySafeValueOperation() {}

    /** Returns a checkpointed random UUID using the default operation name {@code uuid}. */
    public static UUID uuid() {
        return uuid(UUID_NAME);
    }

    /** Returns a checkpointed random UUID. */
    public static UUID uuid(String name) {
        return uuidAsync(name).get();
    }

    /** Returns a future for a checkpointed random UUID using the default operation name {@code uuid}. */
    public static DurableFuture<UUID> uuidAsync() {
        return uuidAsync(UUID_NAME);
    }

    /** Returns a future for a checkpointed random UUID. */
    public static DurableFuture<UUID> uuidAsync(String name) {
        return checkpointedValueAsync(name, UUID_SUBTYPE, UUID.class, UUID::randomUUID);
    }

    /** Returns a checkpointed current instant using the default operation name {@code now}. */
    public static Instant now() {
        return now(NOW_NAME);
    }

    /** Returns a checkpointed current instant. */
    public static Instant now(String name) {
        return nowAsync(name).get();
    }

    /** Returns a future for a checkpointed current instant using the default operation name {@code now}. */
    public static DurableFuture<Instant> nowAsync() {
        return nowAsync(NOW_NAME);
    }

    /** Returns a future for a checkpointed current instant. */
    public static DurableFuture<Instant> nowAsync(String name) {
        return checkpointedValueAsync(name, NOW_SUBTYPE, Instant.class, Instant::now);
    }

    /**
     * Returns a checkpointed pseudorandom {@code double} between {@code 0.0} (inclusive) and {@code 1.0} (exclusive)
     * using the default operation name {@code random}.
     */
    public static double random() {
        return random(RANDOM_NAME);
    }

    /**
     * Returns a checkpointed pseudorandom {@code double} between {@code 0.0} (inclusive) and {@code 1.0} (exclusive).
     */
    public static double random(String name) {
        return randomAsync(name).get();
    }

    /**
     * Returns a future for a checkpointed pseudorandom {@code double} between {@code 0.0} (inclusive) and {@code 1.0}
     * (exclusive) using the default operation name {@code random}.
     */
    public static DurableFuture<Double> randomAsync() {
        return randomAsync(RANDOM_NAME);
    }

    /**
     * Returns a future for a checkpointed pseudorandom {@code double} between {@code 0.0} (inclusive) and {@code 1.0}
     * (exclusive).
     */
    public static DurableFuture<Double> randomAsync(String name) {
        return checkpointedValueAsync(name, RANDOM_SUBTYPE, Double.class, () -> ThreadLocalRandom.current()
                .nextDouble());
    }

    private static <T> DurableFuture<T> checkpointedValueAsync(
            String name, String subType, Class<T> resultType, Supplier<T> supplier) {
        ParameterValidator.validateOperationName(name);
        var context = ExtensionContext.getCurrentContext();
        var result = context.reserve(name)
                .stepAsync(
                        subType,
                        TypeToken.get(resultType),
                        ignored -> CompletableFuture.completedFuture(ExtensionStepResult.succeed(supplier.get())),
                        DurableStepOperation.extensionConfig(
                                DurableStepOperation.StepConfig.builder().build()));
        return CompletionStageDurableFuture.from(context, STEP.name(), name, result);
    }
}
