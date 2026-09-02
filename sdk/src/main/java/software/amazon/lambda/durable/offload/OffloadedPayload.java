// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-owned representation of inline or externally stored serialized payload data.
 *
 * @param mode payload storage mode
 * @param data inline serialized data, when {@code mode} is {@link PayloadStorageMode#INLINE}
 * @param reference external storage reference, when {@code mode} is {@link PayloadStorageMode#REFERENCE}
 * @param preview optional inline preview metadata
 * @param ownerDurableExecutionArn producing durable execution ARN, when integrity metadata is present
 * @param ownerEntityId producing payload entity, when integrity metadata is present
 * @param payloadDigest lowercase SHA-256 digest of the serialized payload, when integrity metadata is present
 * @param producerContext exact producing payload context, when the SDK bound the envelope
 * @param requiresLoad whether the producing offloader must restore the serialized payload
 */
public record OffloadedPayload(
        PayloadStorageMode mode,
        String data,
        String reference,
        Map<String, Object> preview,
        String ownerDurableExecutionArn,
        String ownerEntityId,
        String payloadDigest,
        PayloadOffloadContext producerContext,
        Boolean requiresLoad) {

    public OffloadedPayload {
        Objects.requireNonNull(mode, "mode cannot be null");
        Objects.requireNonNull(requiresLoad, "requiresLoad cannot be null");
        preview = preview == null ? null : immutablePreview(preview);
        if (mode == PayloadStorageMode.INLINE) {
            Objects.requireNonNull(data, "data cannot be null for an inline payload");
            if (reference != null) {
                throw new IllegalArgumentException("reference must be null for an inline payload");
            }
        } else {
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("reference cannot be blank for an externally stored payload");
            }
            if (data != null) {
                throw new IllegalArgumentException("data must be null for an externally stored payload");
            }
            if (!requiresLoad) {
                throw new IllegalArgumentException("externally stored payloads must require load");
            }
        }
        var metadataCount = (ownerDurableExecutionArn == null ? 0 : 1)
                + (ownerEntityId == null ? 0 : 1)
                + (payloadDigest == null ? 0 : 1);
        if (metadataCount != 0 && metadataCount != 3) {
            throw new IllegalArgumentException("payload owner and digest metadata must be provided together");
        }
        if (metadataCount == 3) {
            if (ownerDurableExecutionArn.isBlank()) {
                throw new IllegalArgumentException("ownerDurableExecutionArn cannot be blank");
            }
            if (ownerEntityId.isBlank()) {
                throw new IllegalArgumentException("ownerEntityId cannot be blank");
            }
            if (!payloadDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("payloadDigest must be lowercase SHA-256 hex");
            }
        }
        if (producerContext != null) {
            if (producerContext.originalValue() != null) {
                throw new IllegalArgumentException("producerContext.originalValue must be null");
            }
            if (metadataCount != 3
                    || !producerContext.durableExecutionArn().equals(ownerDurableExecutionArn)
                    || !producerContext.entityId().equals(ownerEntityId)) {
                throw new IllegalArgumentException("producerContext must match payload owner metadata");
            }
        }
    }

    /** Creates a payload with the legacy constructor shape. */
    public OffloadedPayload(
            PayloadStorageMode mode,
            String data,
            String reference,
            Map<String, Object> preview,
            String ownerDurableExecutionArn,
            String ownerEntityId,
            String payloadDigest,
            PayloadOffloadContext producerContext) {
        this(
                mode,
                data,
                reference,
                preview,
                ownerDurableExecutionArn,
                ownerEntityId,
                payloadDigest,
                producerContext,
                true);
    }

    /** Creates an inline payload. */
    public static OffloadedPayload inline(String data) {
        return new OffloadedPayload(PayloadStorageMode.INLINE, data, null, null, null, null, null, null, true);
    }

    /** Creates an integrity-bound inline payload. */
    public static OffloadedPayload inline(
            String data, String ownerDurableExecutionArn, String ownerEntityId, String payloadDigest) {
        return new OffloadedPayload(
                PayloadStorageMode.INLINE,
                data,
                null,
                null,
                ownerDurableExecutionArn,
                ownerEntityId,
                payloadDigest,
                null,
                true);
    }

    /** Creates an externally stored payload. */
    public static OffloadedPayload reference(String reference, Map<String, Object> preview) {
        return new OffloadedPayload(
                PayloadStorageMode.REFERENCE, null, reference, preview, null, null, null, null, true);
    }

    /** Creates an integrity-bound externally stored payload. */
    public static OffloadedPayload reference(
            String reference,
            Map<String, Object> preview,
            String ownerDurableExecutionArn,
            String ownerEntityId,
            String payloadDigest) {
        return new OffloadedPayload(
                PayloadStorageMode.REFERENCE,
                null,
                reference,
                preview,
                ownerDurableExecutionArn,
                ownerEntityId,
                payloadDigest,
                null,
                true);
    }

    /** Returns whether producer ownership and content integrity metadata is present. */
    public boolean hasIntegrityMetadata() {
        return payloadDigest != null;
    }

    /** Returns this payload bound to the exact SDK context that produced the serialized content. */
    public OffloadedPayload bindProducer(PayloadOffloadContext context, String digest) {
        return bindProducer(context, digest, requiresLoad);
    }

    /** Returns this payload bound to the exact SDK context and load semantics that produced the serialized content. */
    public OffloadedPayload bindProducer(PayloadOffloadContext context, String digest, boolean requiresLoad) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(digest, "digest cannot be null");
        var cleanContext = context.withOriginalValue(null);
        if (hasIntegrityMetadata()
                && (!ownerDurableExecutionArn.equals(cleanContext.durableExecutionArn())
                        || !ownerEntityId.equals(cleanContext.entityId())
                        || !payloadDigest.equals(digest)
                        || this.requiresLoad != requiresLoad)) {
            throw new IllegalArgumentException(
                    "payload owner, digest, or load semantics do not match the producing context");
        }
        if (producerContext != null && !producerContext.equals(cleanContext)) {
            throw new IllegalArgumentException("producerContext does not match the producing context");
        }
        return new OffloadedPayload(
                mode,
                data,
                reference,
                preview,
                cleanContext.durableExecutionArn(),
                cleanContext.entityId(),
                digest,
                cleanContext,
                requiresLoad);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutablePreview(Map<String, Object> preview) {
        return (Map<String, Object>) immutableValue(preview, new IdentityHashMap<>());
    }

    private static Object immutableValue(Object value, IdentityHashMap<Object, Object> copies) {
        if (value == null) {
            return null;
        }
        var existing = copies.get(value);
        if (existing != null) {
            return existing;
        }
        if (value instanceof Map<?, ?> map) {
            var target = new LinkedHashMap<String, Object>();
            var immutable = Collections.unmodifiableMap(target);
            copies.put(value, immutable);
            for (var entry : map.entrySet()) {
                var key = Objects.requireNonNull(entry.getKey(), "preview map keys cannot be null");
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("preview map keys must be strings");
                }
                target.put(stringKey, immutableValue(entry.getValue(), copies));
            }
            return immutable;
        }
        if (value instanceof Collection<?> collection) {
            var target = new ArrayList<>();
            List<Object> immutable = Collections.unmodifiableList(target);
            copies.put(value, immutable);
            for (var item : collection) {
                target.add(immutableValue(item, copies));
            }
            return immutable;
        }
        if (value.getClass().isArray()) {
            var target = new ArrayList<>();
            List<Object> immutable = Collections.unmodifiableList(target);
            copies.put(value, immutable);
            for (int index = 0; index < Array.getLength(value); index++) {
                target.add(immutableValue(Array.get(value, index), copies));
            }
            return immutable;
        }
        if (value instanceof CharSequence sequence) {
            return sequence.toString();
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        throw new IllegalArgumentException(
                "preview values must be JSON-compatible maps, collections, arrays, or scalars: "
                        + value.getClass().getName());
    }
}
