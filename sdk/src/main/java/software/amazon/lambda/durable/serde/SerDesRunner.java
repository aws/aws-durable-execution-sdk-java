// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
 * Runs customer SerDes calls and passes {@link SerDesContext} explicitly to composable pipeline stages.
 *
 * <p>Calls execute inline unless an executor is configured. Instances are invocation-scoped so successful
 * deserialization results are cached only for one Lambda invocation. Completed values use a bounded weak-reference
 * cache, while concurrent calls for the same value share one in-flight deserialization.
 */
public final class SerDesRunner {
    static final int MAX_COMPLETED_DESERIALIZATIONS = 256;
    private static final Object CACHE_MISS = new Object();
    private static final Object NULL_VALUE = new Object();

    private final ExecutorService executorService;
    private final Map<CacheKey, CompletableFuture<Object>> inFlightDeserializations = new ConcurrentHashMap<>();
    private final Map<CacheKey, WeakReference<Object>> completedDeserializations =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, WeakReference<Object>> eldest) {
                    return size() > MAX_COMPLETED_DESERIALIZATIONS;
                }
            });

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
        return run("serialize", context, () -> serializeWithContext(serDes, value, context));
    }

    /** Deserializes a value with invocation-scoped caching. */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(SerDes serDes, String data, TypeToken<T> typeToken, SerDesContext context) {
        Objects.requireNonNull(serDes, "serDes cannot be null");
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        Objects.requireNonNull(context, "SerDesContext cannot be null");
        var key = new CacheKey(
                serDes,
                context.durableExecutionArn(),
                context.entityId(),
                context.payloadKind(),
                context.attempt(),
                typeToken,
                hash(data));
        var cached = getCompleted(key);
        if (cached != CACHE_MISS) {
            return (T) unmaskNull(cached);
        }

        var pending = new CompletableFuture<Object>();
        var existing = inFlightDeserializations.putIfAbsent(key, pending);
        if (existing != null) {
            return (T) unmaskNull(join(existing));
        }

        try {
            // A deserialization may have completed between the first cache lookup and this caller claiming
            // the in-flight slot.
            cached = getCompleted(key);
            if (cached != CACHE_MISS) {
                pending.complete(cached);
                return (T) unmaskNull(cached);
            }

            T value = run("deserialize", context, () -> deserializeWithContext(serDes, data, typeToken, context));
            var cacheValue = maskNull(value);
            putCompleted(key, cacheValue);
            pending.complete(cacheValue);
            return value;
        } catch (Throwable failure) {
            pending.completeExceptionally(failure);
            ExceptionHelper.sneakyThrow(failure);
            return null;
        } finally {
            inFlightDeserializations.remove(key, pending);
        }
    }

    private Object getCompleted(CacheKey key) {
        synchronized (completedDeserializations) {
            var reference = completedDeserializations.get(key);
            if (reference == null) {
                return CACHE_MISS;
            }
            var value = reference.get();
            if (value == null) {
                completedDeserializations.remove(key);
                return CACHE_MISS;
            }
            return value;
        }
    }

    private void putCompleted(CacheKey key, Object value) {
        completedDeserializations.put(key, new WeakReference<>(value));
    }

    private static Object maskNull(Object value) {
        return value == null ? NULL_VALUE : value;
    }

    private static Object unmaskNull(Object value) {
        return value == NULL_VALUE ? null : value;
    }

    private static String serializeWithContext(SerDes serDes, Object value, SerDesContext context) {
        if (serDes instanceof ComposableSerDes composable) {
            return composable.serialize(value, context);
        }
        return serDes.serialize(value);
    }

    private static <T> T deserializeWithContext(
            SerDes serDes, String data, TypeToken<T> typeToken, SerDesContext context) {
        if (serDes instanceof ComposableSerDes composable) {
            return composable.deserialize(data, typeToken, context);
        }
        return serDes.deserialize(data, typeToken);
    }

    private <T> T run(String action, SerDesContext context, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        Objects.requireNonNull(context, "SerDesContext cannot be null");
        try {
            if (executorService == null) {
                return supplier.get();
            }
            return CompletableFuture.supplyAsync(supplier, executorService).join();
        } catch (Throwable throwable) {
            var cause = ExceptionHelper.unwrapCompletableFuture(throwable);
            if (cause instanceof Error error) {
                throw error;
            }
            var message = String.format(
                    "Failed to %s %s payload for entity '%s'", action, context.payloadKind(), context.entityId());
            if (cause instanceof RetryableSerDesException) {
                throw new RetryableSerDesException(message, cause);
            }
            throw new SerDesException(message, cause);
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
            var digest = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < data.length(); index++) {
                var codeUnit = data.charAt(index);
                digest.update((byte) (codeUnit >>> 8));
                digest.update((byte) codeUnit);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record CacheKey(
            SerDes serDes,
            String durableExecutionArn,
            String entityId,
            SerDesPayloadKind payloadKind,
            Integer attempt,
            TypeToken<?> typeToken,
            String serializedHash) {
        @Override
        public boolean equals(Object other) {
            return other instanceof CacheKey that
                    && serDes == that.serDes
                    && Objects.equals(durableExecutionArn, that.durableExecutionArn)
                    && Objects.equals(entityId, that.entityId)
                    && payloadKind == that.payloadKind
                    && Objects.equals(attempt, that.attempt)
                    && Objects.equals(typeToken, that.typeToken)
                    && Objects.equals(serializedHash, that.serializedHash);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(serDes);
            result = 31 * result + Objects.hashCode(durableExecutionArn);
            result = 31 * result + Objects.hashCode(entityId);
            result = 31 * result + Objects.hashCode(payloadKind);
            result = 31 * result + Objects.hashCode(attempt);
            result = 31 * result + Objects.hashCode(typeToken);
            return 31 * result + Objects.hashCode(serializedHash);
        }
    }
}
