// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
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
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Executes SDK-managed SerDes calls on a dedicated executor with {@link SerDesContext} installed in thread-local
 * storage.
 *
 * <p>Each runner is scoped to one Lambda invocation. Successful deserializations are cached for that invocation so
 * repeated reads of the same checkpoint payload do not repeat filesystem or other external I/O.
 */
public final class SerDesRunner {
    static final int MAX_COMPLETED_DESERIALIZATIONS = 256;
    private static final Object NULL_VALUE = new Object();

    private final ExecutorService executorService;
    private final ConcurrentHashMap<CacheKey, CompletableFuture<Object>> inFlightDeserializations =
            new ConcurrentHashMap<>();
    private final Map<CacheKey, WeakReference<Object>> completedDeserializations =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, WeakReference<Object>> eldest) {
                    return size() > MAX_COMPLETED_DESERIALIZATIONS;
                }
            });

    public SerDesRunner(ExecutorService executorService) {
        this.executorService = Objects.requireNonNull(executorService, "executorService cannot be null");
    }

    /** Serializes a value with the supplied durable payload context. */
    public String serialize(SerDes serDes, Object value, SerDesContext context) {
        Objects.requireNonNull(serDes, "serDes cannot be null");
        return join(submit(context, () -> serDes.serialize(value)));
    }

    /** Deserializes and caches a value for the current invocation using the supplied durable payload context. */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(SerDes serDes, String data, TypeToken<T> typeToken, SerDesContext context) {
        Objects.requireNonNull(serDes, "serDes cannot be null");
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        Objects.requireNonNull(context, "context cannot be null");

        var key = new CacheKey(serDes, context.durableExecutionArn(), context.entityId(), typeToken, hash(data));
        var cached = getCompleted(key);
        if (cached != null) {
            return cached == NULL_VALUE ? null : (T) cached;
        }

        var pending = new CompletableFuture<Object>();
        var existing = inFlightDeserializations.putIfAbsent(key, pending);
        if (existing != null) {
            var value = join(existing);
            return value == NULL_VALUE ? null : (T) value;
        }

        try {
            cached = getCompleted(key);
            if (cached != null) {
                pending.complete(cached);
                return cached == NULL_VALUE ? null : (T) cached;
            }

            var value = join(submit(context, () -> serDes.deserialize(data, typeToken)));
            var cacheValue = value == null ? NULL_VALUE : value;
            putCompleted(key, cacheValue);
            pending.complete(cacheValue);
            return value;
        } catch (Throwable failure) {
            pending.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlightDeserializations.remove(key, pending);
        }
    }

    private Object getCompleted(CacheKey key) {
        synchronized (completedDeserializations) {
            var reference = completedDeserializations.get(key);
            if (reference == null) {
                return null;
            }
            var value = reference.get();
            if (value == null) {
                completedDeserializations.remove(key);
            }
            return value;
        }
    }

    private void putCompleted(CacheKey key, Object value) {
        completedDeserializations.put(key, new WeakReference<>(value));
    }

    private <T> CompletableFuture<T> submit(SerDesContext context, Supplier<T> action) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
        return CompletableFuture.supplyAsync(() -> SerDesContext.callWithContext(context, action), executorService);
    }

    private static <T> T join(CompletableFuture<T> future) {
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
            SerDes serDes, String durableExecutionArn, String entityId, TypeToken<?> typeToken, String dataHash) {
        @Override
        public boolean equals(Object other) {
            return other instanceof CacheKey that
                    && serDes == that.serDes
                    && Objects.equals(durableExecutionArn, that.durableExecutionArn)
                    && Objects.equals(entityId, that.entityId)
                    && Objects.equals(typeToken, that.typeToken)
                    && Objects.equals(dataHash, that.dataHash);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(serDes);
            result = 31 * result + Objects.hashCode(durableExecutionArn);
            result = 31 * result + Objects.hashCode(entityId);
            result = 31 * result + Objects.hashCode(typeToken);
            return 31 * result + Objects.hashCode(dataHash);
        }
    }
}
