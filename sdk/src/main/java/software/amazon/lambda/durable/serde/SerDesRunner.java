// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Runs customer SerDes calls on the configured SerDes executor with the correct {@link SerDesContext}.
 *
 * <p>Instances are invocation-scoped so successful deserialization results are cached only for one Lambda invocation.
 */
public final class SerDesRunner {
    private static final Object NULL_VALUE = new Object();

    private final ExecutorService executorService;
    private final Map<CacheKey, Object> deserializationCache = new ConcurrentHashMap<>();

    public SerDesRunner(ExecutorService executorService) {
        this.executorService = Objects.requireNonNull(executorService, "executorService cannot be null");
    }

    /** Serializes a value with the supplied durable payload context. */
    public String serialize(SerDes serDes, Object value, SerDesContext context) {
        return run("serialize", context, () -> serDes.serialize(value));
    }

    /** Deserializes a value with invocation-scoped caching. */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(SerDes serDes, String data, TypeToken<T> typeToken, SerDesContext context) {
        Objects.requireNonNull(context, "SerDesContext cannot be null");
        var key = new CacheKey(
                context.durableExecutionArn(),
                context.entityId(),
                context.payloadKind(),
                context.attempt(),
                typeToken,
                hash(data));
        var cached = deserializationCache.get(key);
        if (cached != null) {
            return cached == NULL_VALUE ? null : (T) cached;
        }

        T value = run("deserialize", context, () -> serDes.deserialize(data, typeToken));
        deserializationCache.putIfAbsent(key, value == null ? NULL_VALUE : value);
        return value;
    }

    private <T> T run(String action, SerDesContext context, Supplier<T> supplier) {
        Objects.requireNonNull(context, "SerDesContext cannot be null");
        try {
            return CompletableFuture.supplyAsync(
                            () -> {
                                SerDesContextHolder.set(context);
                                try {
                                    return supplier.get();
                                } finally {
                                    SerDesContextHolder.clear();
                                }
                            },
                            executorService)
                    .join();
        } catch (Throwable throwable) {
            var cause = ExceptionHelper.unwrapCompletableFuture(throwable);
            throw new SerDesException(
                    String.format(
                            "Failed to %s %s payload for entity '%s'",
                            action, context.payloadKind(), context.entityId()),
                    cause);
        }
    }

    private static String hash(String data) {
        if (data == null) {
            return "null";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record CacheKey(
            String durableExecutionArn,
            String entityId,
            SerDesPayloadKind payloadKind,
            Integer attempt,
            TypeToken<?> typeToken,
            String serializedHash) {}
}
