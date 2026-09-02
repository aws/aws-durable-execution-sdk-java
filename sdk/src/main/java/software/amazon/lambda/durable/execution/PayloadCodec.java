// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.PayloadOffloaders;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.offload.internal.PayloadOffloadTracking;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Invocation-scoped pipeline that composes object serialization, payload offloading, loading, and bounded caching.
 *
 * <p>Legacy raw serialized strings remain readable. New offloaded values use a reserved, versioned prefix so arbitrary
 * JSON payloads cannot be mistaken for SDK envelopes.
 */
public final class PayloadCodec {
    static final int MAX_COMPLETED_CACHE_ENTRIES = 256;
    private static final String ENVELOPE_MARKER = "@aws-durable-payload:";
    private static final String ENVELOPE_PREFIX = "@aws-durable-payload:v1:";
    private static final Object CACHE_MISS = new Object();
    private static final Object NULL_VALUE = new Object();
    private static final TypeToken<OffloadedPayload> OFFLOADED_PAYLOAD_TYPE = TypeToken.get(OffloadedPayload.class);
    private static final ObjectMapper ENVELOPE_OBJECT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final SerDes ENVELOPE_SER_DES = new JacksonSerDes(ENVELOPE_OBJECT_MAPPER);

    private final ExecutorService executorService;
    private final BooleanSupplier runInlineOnCurrentThread;
    private final Map<PayloadCacheKey, CompletableFuture<Object>> inFlightLoads = new ConcurrentHashMap<>();
    private final Map<DeserializedCacheKey, CompletableFuture<Object>> inFlightDeserializations =
            new ConcurrentHashMap<>();
    private final BoundedWeakCache<PayloadCacheKey> loadedPayloadCache = new BoundedWeakCache<>();
    private final BoundedWeakCache<DeserializedCacheKey> deserializedPayloadCache = new BoundedWeakCache<>();

    /**
     * Creates an invocation-scoped codec.
     *
     * @param executorService executor for blocking offloader calls, or null to execute inline
     */
    public PayloadCodec(ExecutorService executorService) {
        this(executorService, () -> false);
    }

    PayloadCodec(ExecutorService executorService, BooleanSupplier runInlineOnCurrentThread) {
        this.executorService = executorService;
        this.runInlineOnCurrentThread =
                Objects.requireNonNull(runInlineOnCurrentThread, "runInlineOnCurrentThread cannot be null");
    }

    /** Returns whether a checkpoint value uses the SDK payload offload envelope. */
    public static boolean isOffloadEnvelope(String checkpointPayload) {
        return checkpointPayload != null && checkpointPayload.startsWith(ENVELOPE_MARKER);
    }

    /** Returns the UTF-8 byte size of an SDK payload envelope. */
    public static int envelopeSizeBytes(OffloadedPayload payload) {
        return encodeEnvelope(payload).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Serializes and optionally offloads a value. */
    public String serialize(Object value, SerDes serDes, PayloadOffloader offloader, PayloadOffloadContext context) {
        var serialized = serDes.serialize(value);
        return offloadSerialized(serialized, value, offloader, context, true);
    }

    /** Rebinds already serialized payload data from a producer operation to a forwarding operation's policy. */
    public String rebindSerializedPayload(
            String checkpointPayload,
            PayloadOffloader sourceOffloader,
            PayloadOffloadContext sourceContext,
            PayloadOffloader targetOffloader,
            PayloadOffloadContext targetContext) {
        var serialized = resolve(checkpointPayload, sourceOffloader, sourceContext);
        return offloadSerialized(serialized, null, targetOffloader, targetContext, true);
    }

    /** Encodes already serialized SDK payload data, escaping the reserved envelope marker when necessary. */
    public String serializePreEncodedPayload(
            String serializedPayload, PayloadOffloader offloader, PayloadOffloadContext context) {
        return offloadSerialized(serializedPayload, null, offloader, context, true);
    }

    /** Offloads already serialized data without interpreting it as an existing SDK payload envelope. */
    public String offloadSerializedPayload(
            String serializedPayload, PayloadOffloader offloader, PayloadOffloadContext context) {
        return offloadSerialized(serializedPayload, null, offloader, context, false);
    }

    private String offloadSerialized(
            String serialized,
            Object originalValue,
            PayloadOffloader offloader,
            PayloadOffloadContext context,
            boolean escapeReservedMarker) {
        var effectiveOffloader = effectiveOffloader(offloader);
        if (serialized == null) {
            return serialized;
        }
        if (effectiveOffloader == null) {
            if (!escapeReservedMarker || !serialized.startsWith(ENVELOPE_MARKER)) {
                return serialized;
            }
            if (offloader != null) {
                PayloadOffloadTracking.record(context, offloader);
            }
            return encodeEnvelope(OffloadedPayload.inline(serialized).bindProducer(context, hash(serialized), false));
        }

        PayloadOffloadTracking.record(context, effectiveOffloader);
        var serializationContext = context.withOriginalValue(originalValue);
        var payload = runOffloadTask(
                () -> effectiveOffloader.offload(serialized, serializationContext), "store", serializationContext);
        if (payload == null) {
            throw new PayloadOffloadException("Payload offloader returned null for " + describe(context));
        }
        try {
            payload = payload.bindProducer(context, hash(serialized), true);
        } catch (IllegalArgumentException e) {
            throw new PayloadOffloadException(
                    "Payload offloader returned inconsistent metadata for " + describe(context), e);
        }
        validateOwner(payload, context);
        verifyDigest(payload, serialized, context);
        return encodeEnvelope(payload);
    }

    /** Deserializes a raw legacy payload or an SDK offload envelope. */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(
            String checkpointPayload,
            TypeToken<T> typeToken,
            SerDes serDes,
            PayloadOffloader offloader,
            PayloadOffloadContext context) {
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        Objects.requireNonNull(serDes, "serDes cannot be null");
        var serialized = resolve(checkpointPayload, offloader, context);
        var key = new DeserializedCacheKey(
                serDes,
                context.durableExecutionArn(),
                context.entityId(),
                context.payloadKind(),
                context.attempt(),
                typeToken.getType().getTypeName(),
                hash(serialized));
        var cached = deserializedPayloadCache.get(key);
        if (cached != CACHE_MISS) {
            return (T) unmaskNull(cached);
        }

        return (T) unmaskNull(loadOnce(
                key,
                inFlightDeserializations,
                deserializedPayloadCache,
                () -> maskNull(serDes.deserialize(serialized, typeToken))));
    }

    /** Resolves an SDK envelope to the serialized text produced by SerDes without deserializing the object. */
    public String resolveSerializedPayload(
            String checkpointPayload, PayloadOffloader offloader, PayloadOffloadContext context) {
        return resolve(checkpointPayload, offloader, context);
    }

    /** Validates that a value is a supported, well-formed SDK envelope owned by the supplied payload context. */
    public void validateEnvelope(String checkpointPayload, PayloadOffloadContext context) {
        if (checkpointPayload == null || !checkpointPayload.startsWith(ENVELOPE_MARKER)) {
            throw new PayloadOffloadException("Expected payload offload envelope for " + describe(context));
        }
        if (!checkpointPayload.startsWith(ENVELOPE_PREFIX)) {
            throw new PayloadOffloadException(
                    "Unsupported or malformed payload offload envelope version for " + describe(context));
        }
        validateOwner(decodeEnvelope(checkpointPayload, context), context);
    }

    /** Resolves an envelope using its embedded producer context when no consuming execution context is available. */
    public String resolveSerializedPayloadUsingProducerContext(String checkpointPayload, PayloadOffloader offloader) {
        if (checkpointPayload == null || !checkpointPayload.startsWith(ENVELOPE_MARKER)) {
            return checkpointPayload;
        }
        if (!checkpointPayload.startsWith(ENVELOPE_PREFIX)) {
            throw new PayloadOffloadException("Unsupported or malformed payload offload envelope version");
        }
        var payload = decodeEnvelope(checkpointPayload, null);
        if (payload.producerContext() == null) {
            throw new PayloadOffloadException("Payload envelope is missing producer context");
        }
        return resolve(checkpointPayload, offloader, payload.producerContext());
    }

    /** Clears invocation-scoped payload caches. */
    public void clear() {
        inFlightLoads.clear();
        inFlightDeserializations.clear();
        loadedPayloadCache.clear();
        deserializedPayloadCache.clear();
    }

    private String resolve(String checkpointPayload, PayloadOffloader offloader, PayloadOffloadContext context) {
        if (checkpointPayload == null || !checkpointPayload.startsWith(ENVELOPE_MARKER)) {
            return checkpointPayload;
        }
        if (!checkpointPayload.startsWith(ENVELOPE_PREFIX)) {
            throw new PayloadOffloadException(
                    "Unsupported or malformed payload offload envelope version for " + describe(context));
        }

        var payload = decodeEnvelope(checkpointPayload, context);
        validateOwner(payload, context);
        var effectiveOffloader = effectiveOffloader(offloader);
        var loadOffloader = payload.requiresLoad() ? effectiveOffloader : null;
        var key = payloadCacheKey(context, checkpointPayload, loadOffloader);
        var cached = loadedPayloadCache.get(key);
        if (cached != CACHE_MISS) {
            return (String) cached;
        }

        return (String) loadOnce(key, inFlightLoads, loadedPayloadCache, () -> {
            final String serialized;
            if (payload.requiresLoad()) {
                if (loadOffloader == null) {
                    throw new PayloadOffloadException(
                            "Payload requires its producing offloader but no payload offloader is configured for "
                                    + describe(context));
                }
                var loadContext = payload.producerContext() != null ? payload.producerContext() : context;
                serialized = runOffloadTask(() -> loadOffloader.load(payload, loadContext), "load", context);
            } else {
                serialized = payload.data();
            }
            if (serialized == null) {
                throw new PayloadOffloadException("Payload offloader returned null while loading " + describe(context));
            }
            verifyDigest(payload, serialized, context);
            return serialized;
        });
    }

    private static OffloadedPayload decodeEnvelope(String checkpointPayload, PayloadOffloadContext context) {
        try {
            var envelopeJson = checkpointPayload.substring(ENVELOPE_PREFIX.length());
            var envelopeNode = ENVELOPE_OBJECT_MAPPER.readTree(envelopeJson);
            if (envelopeNode == null
                    || !envelopeNode.isObject()
                    || !envelopeNode.path("mode").isTextual()
                    || !envelopeNode.path("requiresLoad").isBoolean()) {
                throw new PayloadOffloadException(
                        "Payload envelope has invalid scalar field types for " + describe(context));
            }
            var payload = ENVELOPE_SER_DES.deserialize(envelopeJson, OFFLOADED_PAYLOAD_TYPE);
            if (payload == null) {
                throw new PayloadOffloadException("Payload envelope decoded to null for " + describe(context));
            }
            return payload;
        } catch (JsonProcessingException | SerDesException e) {
            throw new PayloadOffloadException("Invalid payload offload envelope for " + describe(context), e);
        }
    }

    private static void validateOwner(OffloadedPayload payload, PayloadOffloadContext context) {
        if (!payload.hasIntegrityMetadata() || payload.producerContext() == null) {
            throw new PayloadOffloadException(
                    "Payload envelope is missing producer ownership or integrity metadata for " + describe(context));
        }
        var sameOwner = payload.ownerDurableExecutionArn().equals(context.durableExecutionArn())
                && payload.ownerEntityId().equals(context.entityId());
        if (!sameOwner
                && context.payloadKind() != SerDesPayloadKind.INPUT
                && context.operationType() != OperationType.CHAINED_INVOKE) {
            throw new PayloadOffloadException("Payload belongs to a different durable entity");
        }
    }

    private static void verifyDigest(OffloadedPayload payload, String serialized, PayloadOffloadContext context) {
        if (payload.hasIntegrityMetadata() && !hash(serialized).equals(payload.payloadDigest())) {
            throw new PayloadOffloadException("Payload digest does not match stored content for " + describe(context));
        }
    }

    private <T> T runOffloadTask(Supplier<T> task, String action, PayloadOffloadContext context) {
        try {
            if (executorService == null || runInlineOnCurrentThread.getAsBoolean()) {
                return task.get();
            }
            return CompletableFuture.supplyAsync(task, executorService).join();
        } catch (Throwable throwable) {
            var cause = ExceptionHelper.unwrapCompletableFuture(throwable);
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof PayloadOffloadException payloadOffloadException) {
                throw payloadOffloadException;
            }
            throw new PayloadOffloadException("Failed to " + action + " payload for " + describe(context), cause);
        }
    }

    private static <K> Object loadOnce(
            K key, Map<K, CompletableFuture<Object>> inFlight, BoundedWeakCache<K> completed, Supplier<Object> loader) {
        var cached = completed.get(key);
        if (cached != CACHE_MISS) {
            return cached;
        }

        var pending = new CompletableFuture<Object>();
        var existing = inFlight.putIfAbsent(key, pending);
        if (existing != null) {
            return join(existing);
        }

        try {
            cached = completed.get(key);
            if (cached != CACHE_MISS) {
                pending.complete(cached);
                return cached;
            }
            var value = loader.get();
            completed.put(key, value);
            pending.complete(value);
            return value;
        } catch (Throwable failure) {
            pending.completeExceptionally(failure);
            ExceptionHelper.sneakyThrow(failure);
            return null;
        } finally {
            inFlight.remove(key, pending);
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

    private static Object maskNull(Object value) {
        return value == null ? NULL_VALUE : value;
    }

    private static Object unmaskNull(Object value) {
        return value == NULL_VALUE ? null : value;
    }

    private static PayloadOffloader effectiveOffloader(PayloadOffloader offloader) {
        return offloader == null || PayloadOffloaders.isDisabled(offloader) ? null : offloader;
    }

    private static String encodeEnvelope(OffloadedPayload payload) {
        Objects.requireNonNull(payload, "payload cannot be null");
        try {
            return ENVELOPE_PREFIX + ENVELOPE_SER_DES.serialize(payload);
        } catch (SerDesException e) {
            throw new PayloadOffloadException("Failed to encode payload offload envelope", e);
        }
    }

    private static PayloadCacheKey payloadCacheKey(
            PayloadOffloadContext context, String checkpointPayload, PayloadOffloader offloader) {
        return new PayloadCacheKey(
                offloader,
                context.durableExecutionArn(),
                context.entityId(),
                context.payloadKind(),
                context.attempt(),
                hash(checkpointPayload));
    }

    private static String describe(PayloadOffloadContext context) {
        return context == null ? "payload" : context.payloadKind() + " payload '" + context.entityId() + "'";
    }

    private static String hash(String value) {
        if (value == null) {
            return "null";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static final class BoundedWeakCache<K> {
        private final Map<K, WeakReference<Object>> values =
                Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<K, WeakReference<Object>> eldest) {
                        return size() > MAX_COMPLETED_CACHE_ENTRIES;
                    }
                });

        private Object get(K key) {
            synchronized (values) {
                var reference = values.get(key);
                if (reference == null) {
                    return CACHE_MISS;
                }
                var value = reference.get();
                if (value == null) {
                    values.remove(key);
                    return CACHE_MISS;
                }
                return value;
            }
        }

        private void put(K key, Object value) {
            values.put(key, new WeakReference<>(value));
        }

        private void clear() {
            values.clear();
        }
    }

    private record PayloadCacheKey(
            PayloadOffloader offloader,
            String durableExecutionArn,
            String entityId,
            SerDesPayloadKind payloadKind,
            Integer attempt,
            String checkpointPayloadHash) {
        @Override
        public boolean equals(Object other) {
            return other instanceof PayloadCacheKey that
                    && offloader == that.offloader
                    && Objects.equals(durableExecutionArn, that.durableExecutionArn)
                    && Objects.equals(entityId, that.entityId)
                    && payloadKind == that.payloadKind
                    && Objects.equals(attempt, that.attempt)
                    && Objects.equals(checkpointPayloadHash, that.checkpointPayloadHash);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(offloader);
            result = 31 * result + Objects.hashCode(durableExecutionArn);
            result = 31 * result + Objects.hashCode(entityId);
            result = 31 * result + Objects.hashCode(payloadKind);
            result = 31 * result + Objects.hashCode(attempt);
            return 31 * result + Objects.hashCode(checkpointPayloadHash);
        }
    }

    private record DeserializedCacheKey(
            SerDes serDes,
            String durableExecutionArn,
            String entityId,
            SerDesPayloadKind payloadKind,
            Integer attempt,
            String targetType,
            String serializedPayloadHash) {
        @Override
        public boolean equals(Object other) {
            return other instanceof DeserializedCacheKey that
                    && serDes == that.serDes
                    && Objects.equals(durableExecutionArn, that.durableExecutionArn)
                    && Objects.equals(entityId, that.entityId)
                    && payloadKind == that.payloadKind
                    && Objects.equals(attempt, that.attempt)
                    && Objects.equals(targetType, that.targetType)
                    && Objects.equals(serializedPayloadHash, that.serializedPayloadHash);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(serDes);
            result = 31 * result + Objects.hashCode(durableExecutionArn);
            result = 31 * result + Objects.hashCode(entityId);
            result = 31 * result + Objects.hashCode(payloadKind);
            result = 31 * result + Objects.hashCode(attempt);
            result = 31 * result + Objects.hashCode(targetType);
            return 31 * result + Objects.hashCode(serializedPayloadHash);
        }
    }
}
