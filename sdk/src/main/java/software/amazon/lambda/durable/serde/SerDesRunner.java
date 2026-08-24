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
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Runs customer SerDes calls with the correct {@link SerDesContext}.
 *
 * <p>Calls execute inline unless an executor is configured. Instances are invocation-scoped so successful
 * deserialization results are cached only for one Lambda invocation.
 */
public final class SerDesRunner {
    private final ExecutorService executorService;
    private final Map<CacheKey, CompletableFuture<Object>> deserializationCache = new ConcurrentHashMap<>();

    /**
     * Creates an invocation-scoped runner.
     *
     * @param executorService executor for SerDes calls, or {@code null} to execute inline
     */
    public SerDesRunner(ExecutorService executorService) {
        this.executorService = executorService;
    }

    /** Serializes a value with the supplied durable payload context. */
    public String serialize(SerDes serDes, Object value, SerDesContext context) {
        Objects.requireNonNull(serDes, "serDes cannot be null");
        return run("serialize", context, () -> serDes.serialize(value));
    }

    /** Deserializes a value with invocation-scoped caching. */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(SerDes serDes, String data, TypeToken<T> typeToken, SerDesContext context) {
        Objects.requireNonNull(serDes, "serDes cannot be null");
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        Objects.requireNonNull(context, "SerDesContext cannot be null");
        var key = new CacheKey(
                context.durableExecutionArn(),
                context.entityId(),
                context.payloadKind(),
                context.attempt(),
                typeToken,
                hash(data));
        var pending = new CompletableFuture<Object>();
        var existing = deserializationCache.putIfAbsent(key, pending);
        if (existing != null) {
            return (T) join(existing);
        }

        try {
            T value = run("deserialize", context, () -> serDes.deserialize(data, typeToken));
            pending.complete(value);
            return value;
        } catch (Throwable failure) {
            pending.completeExceptionally(failure);
            deserializationCache.remove(key, pending);
            ExceptionHelper.sneakyThrow(failure);
            return null;
        }
    }

    private <T> T run(String action, SerDesContext context, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        Objects.requireNonNull(context, "SerDesContext cannot be null");
        try {
            if (executorService == null) {
                return runWithContext(context, supplier);
            }
            return CompletableFuture.supplyAsync(() -> runWithContext(context, supplier), executorService)
                    .join();
        } catch (Throwable throwable) {
            var cause = ExceptionHelper.unwrapCompletableFuture(throwable);
            var message = String.format(
                    "Failed to %s %s payload for entity '%s'", action, context.payloadKind(), context.entityId());
            if (cause instanceof RetryableSerDesException) {
                throw new RetryableSerDesException(message, cause);
            }
            throw new SerDesException(message, cause);
        }
    }

    private static <T> T runWithContext(SerDesContext context, Supplier<T> supplier) {
        var previous = SerDesContextHolder.get();
        SerDesContextHolder.set(context);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                SerDesContextHolder.clear();
            } else {
                SerDesContextHolder.set(previous);
            }
        }
    }

    private static Object join(CompletableFuture<Object> future) {
        try {
            return future.join();
        } catch (Throwable failure) {
            ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(failure));
            return null;
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
