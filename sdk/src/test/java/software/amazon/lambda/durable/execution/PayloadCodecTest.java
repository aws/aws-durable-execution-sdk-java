// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class PayloadCodecTest {
    private static final String ENVELOPE_PREFIX = "@aws-durable-payload:v1:";
    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();

    private ExecutorService executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void legacyPayloadRemainsReadable() {
        var codec = codec();
        var value = codec.deserialize(
                "{\"value\":\"legacy\"}", TypeToken.get(TestValue.class), new JacksonSerDes(), null, context());

        assertEquals("legacy", value.value());
    }

    @Test
    void referencePayloadIsLoadedOnceAndDeserializedObjectIsCached() {
        var offloader = new InMemoryOffloader();
        var writer = codec();
        var serDes = new JacksonSerDes();
        var payload = writer.serialize(new TestValue("stored"), serDes, offloader, context());
        writer.clear();

        var reader = codec();
        var first = reader.deserialize(payload, TypeToken.get(TestValue.class), serDes, offloader, context());
        var second = reader.deserialize(payload, TypeToken.get(TestValue.class), serDes, offloader, context());

        assertEquals("stored", first.value());
        assertSame(first, second);
        assertEquals(1, offloader.loadCount.get());
    }

    @Test
    void referenceIsLoadedAndVerifiedBeforeFirstDeserialization() {
        var offloader = new InMemoryOffloader();
        var codec = codec();
        var serDes = new JacksonSerDes();
        var payload = codec.serialize(new TestValue("stored"), serDes, offloader, context());

        var value = codec.deserialize(payload, TypeToken.get(TestValue.class), serDes, offloader, context());

        assertEquals("stored", value.value());
        assertEquals(1, offloader.loadCount.get());
    }

    @Test
    void inlinePayloadUsesLoadPathOnInitialAndFreshCodecDeserialization() {
        var loadCount = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.inline(Base64.getEncoder()
                        .encodeToString(serializedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadCount.incrementAndGet();
                return new String(Base64.getDecoder().decode(payload.data()), java.nio.charset.StandardCharsets.UTF_8);
            }
        };
        var serDes = new JacksonSerDes();
        var writer = codec();
        var payload = writer.serialize(new TestValue("stored"), serDes, offloader, context());

        var initial = writer.deserialize(payload, TypeToken.get(TestValue.class), serDes, offloader, context());
        var replay = new PayloadCodec(null)
                .deserialize(payload, TypeToken.get(TestValue.class), serDes, offloader, context());

        assertEquals("stored", initial.value());
        assertEquals("stored", replay.value());
        assertEquals(2, loadCount.get());
    }

    @Test
    void concurrentDeserializationSharesOneLoadAndOneObject() {
        var offloader = new InMemoryOffloader() {
            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return super.load(payload, context);
            }
        };
        var serDes = new JacksonSerDes();
        var writer = codec();
        var payload = writer.serialize(new TestValue("stored"), serDes, offloader, context());
        writer.clear();
        var reader = codec();

        var first = CompletableFuture.supplyAsync(
                () -> reader.deserialize(payload, TypeToken.get(TestValue.class), serDes, offloader, context()));
        var second = CompletableFuture.supplyAsync(
                () -> reader.deserialize(payload, TypeToken.get(TestValue.class), serDes, offloader, context()));

        assertSame(first.join(), second.join());
        assertEquals(1, offloader.loadCount.get());
    }

    @Test
    void differentSerDesInstancesHaveIndependentObjectCaches() {
        var codec = codec();
        SerDes firstSerDes = new FixedSerDes("one");
        SerDes secondSerDes = new FixedSerDes("two");

        var first = codec.deserialize("payload", TypeToken.get(TestValue.class), firstSerDes, null, context());
        var second = codec.deserialize("payload", TypeToken.get(TestValue.class), secondSerDes, null, context());

        assertEquals("one", first.value());
        assertEquals("two", second.value());
    }

    @Test
    void changedSerializedDataInvalidatesObjectCache() {
        var codec = codec();
        var serDes = new JacksonSerDes();

        var first = codec.deserialize("{\"value\":\"one\"}", TypeToken.get(TestValue.class), serDes, null, context());
        var second = codec.deserialize("{\"value\":\"two\"}", TypeToken.get(TestValue.class), serDes, null, context());

        assertEquals("one", first.value());
        assertEquals("two", second.value());
    }

    @Test
    void attemptsHaveIndependentCachesWhenReferenceIsReused() {
        var codec = codec();
        var serDes = new JacksonSerDes();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.reference("memory://shared", null);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return context.attempt() == 1 ? "{\"value\":\"one\"}" : "{\"value\":\"two\"}";
            }
        };

        var firstPayload = codec.serialize(new TestValue("one"), serDes, offloader, context(1));
        var secondPayload = codec.serialize(new TestValue("two"), serDes, offloader, context(2));

        assertTrue(!firstPayload.equals(secondPayload));
        assertEquals(
                "one",
                codec.deserialize(firstPayload, TypeToken.get(TestValue.class), serDes, offloader, context(1))
                        .value());
        assertEquals(
                "two",
                codec.deserialize(secondPayload, TypeToken.get(TestValue.class), serDes, offloader, context(2))
                        .value());
    }

    @Test
    void disabledOffloaderKeepsLegacyInlineFormat() {
        var codec = codec();
        var payload =
                codec.serialize(new TestValue("inline"), new JacksonSerDes(), PayloadOffloader.disabled(), context());

        assertEquals("{\"value\":\"inline\"}", payload);
    }

    @Test
    void disabledOffloaderEscapesReservedMarkerAndRoundTripsCustomSerDes() {
        var marker = "@aws-durable-payload:v2:{}";
        SerDes serDes = new PassThroughSerDes();
        var writer = codec();

        var payload = writer.serialize(marker, serDes, PayloadOffloader.disabled(), context());
        var initial = writer.deserialize(
                payload, TypeToken.get(String.class), serDes, PayloadOffloader.disabled(), context());
        var replay = new PayloadCodec(null)
                .deserialize(payload, TypeToken.get(String.class), serDes, PayloadOffloader.disabled(), context());

        assertTrue(payload.startsWith("@aws-durable-payload:v1:"));
        assertEquals(marker, initial);
        assertEquals(marker, replay);
    }

    @Test
    void escapedInlineEnvelopeBypassesConsumerOffloader() {
        var marker = "@aws-durable-payload:v2:{}";
        var loadCount = new AtomicInteger();
        var consumerOffloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                throw new AssertionError("offload should not be called");
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadCount.incrementAndGet();
                return "transformed";
            }
        };
        var payload = codec().serialize(marker, new PassThroughSerDes(), PayloadOffloader.disabled(), context());

        var restored = codec().deserialize(
                        payload, TypeToken.get(String.class), new PassThroughSerDes(), consumerOffloader, context());

        assertEquals(marker, restored);
        assertEquals(0, loadCount.get());
    }

    @Test
    void activeInlineOffloaderIsRequiredDuringReplay() {
        var producerOffloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.inline(Base64.getEncoder()
                        .encodeToString(serializedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return new String(Base64.getDecoder().decode(payload.data()), java.nio.charset.StandardCharsets.UTF_8);
            }
        };
        var payload = codec().serialize(new TestValue("stored"), new JacksonSerDes(), producerOffloader, context());

        var error = assertThrows(PayloadOffloadException.class, () -> codec().deserialize(
                        payload, TypeToken.get(TestValue.class), new JacksonSerDes(), null, context()));

        assertTrue(error.getMessage().contains("requires its producing offloader"));
    }

    @Test
    void versionedEnvelopeWithoutLoadSemanticsFailsClosed() {
        var marker = "@aws-durable-payload:v2:{}";
        var payload = codec().serialize(marker, new PassThroughSerDes(), PayloadOffloader.disabled(), context());
        var missingLoadSemantics = payload.replace(",\"requiresLoad\":false", "");
        assertTrue(!payload.equals(missingLoadSemantics));

        assertThrows(PayloadOffloadException.class, () -> codec().deserialize(
                        missingLoadSemantics,
                        TypeToken.get(String.class),
                        new PassThroughSerDes(),
                        PayloadOffloader.disabled(),
                        context()));
    }

    @Test
    void numericEnvelopeModeFailsClosed() {
        var payload = codec().serialize(
                        "@aws-durable-payload:v2:{}", new PassThroughSerDes(), PayloadOffloader.disabled(), context());
        var numericMode = payload.replace("\"mode\":\"INLINE\"", "\"mode\":0");
        assertTrue(!payload.equals(numericMode));

        assertThrows(PayloadOffloadException.class, () -> codec().deserialize(
                        numericMode,
                        TypeToken.get(String.class),
                        new PassThroughSerDes(),
                        PayloadOffloader.disabled(),
                        context()));
    }

    @Test
    void stringEnvelopeLoadFlagFailsClosed() {
        var payload = codec().serialize(
                        "@aws-durable-payload:v2:{}", new PassThroughSerDes(), PayloadOffloader.disabled(), context());
        var stringLoadFlag = payload.replace("\"requiresLoad\":false", "\"requiresLoad\":\"false\"");
        assertTrue(!payload.equals(stringLoadFlag));

        assertThrows(PayloadOffloadException.class, () -> codec().deserialize(
                        stringLoadFlag,
                        TypeToken.get(String.class),
                        new PassThroughSerDes(),
                        PayloadOffloader.disabled(),
                        context()));
    }

    @Test
    void coercedEnvelopeScalarTypesFailClosed() {
        var disabled = PayloadOffloader.disabled();
        var inlinePayload =
                codec().serialize("@aws-durable-payload:v2:{}", new PassThroughSerDes(), disabled, context());
        var referenceOffloader = new InMemoryOffloader();
        var referencePayload =
                codec().serialize(new TestValue("stored"), new JacksonSerDes(), referenceOffloader, context());

        assertInvalidEnvelope(mutateEnvelope(inlinePayload, envelope -> envelope.put("data", 123)), disabled);
        assertInvalidEnvelope(
                mutateEnvelope(referencePayload, envelope -> envelope.put("reference", 123)), referenceOffloader);
        assertInvalidEnvelope(
                mutateEnvelope(inlinePayload, envelope -> envelope.put("ownerDurableExecutionArn", 123)), disabled);
        assertInvalidEnvelope(
                mutateEnvelope(
                        inlinePayload, envelope -> producerContext(envelope).put("attempt", "1")),
                disabled);
        assertInvalidEnvelope(
                mutateEnvelope(
                        inlinePayload, envelope -> producerContext(envelope).put("attempt", 1.5)),
                disabled);
        assertInvalidEnvelope(
                mutateEnvelope(
                        inlinePayload, envelope -> producerContext(envelope).put("operationType", 1)),
                disabled);
        assertInvalidEnvelope(
                mutateEnvelope(
                        inlinePayload, envelope -> producerContext(envelope).put("operationSubType", 1)),
                disabled);
    }

    @Test
    void opaqueSerializedPayloadPreservesReservedMarkerWithoutOffloader() {
        var marker = "@aws-durable-payload:v2:{}";

        var payload = codec().offloadSerializedPayload(marker, PayloadOffloader.disabled(), context());

        assertEquals(marker, payload);
    }

    @Test
    void nullPayloadSkipsOffloaderAndRemainsNull() {
        var codec = codec();
        var offloadCount = new AtomicInteger();
        var offloader = new InMemoryOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                offloadCount.incrementAndGet();
                return super.offload(serializedPayload, context);
            }
        };

        var payload = codec.serialize(null, new JacksonSerDes(), offloader, context());

        assertNull(payload);
        assertNull(codec.deserialize(payload, TypeToken.get(String.class), new JacksonSerDes(), offloader, context()));
        assertEquals(0, offloadCount.get());
    }

    @Test
    void externalEnvelopeRequiresConfiguredOffloader() {
        var offloader = new InMemoryOffloader();
        var writer = codec();
        var payload = writer.serialize(new TestValue("stored"), new JacksonSerDes(), offloader, context());
        writer.clear();

        var reader = codec();
        assertThrows(
                PayloadOffloadException.class,
                () -> reader.deserialize(
                        payload, TypeToken.get(TestValue.class), new JacksonSerDes(), null, context()));
    }

    @Test
    void offloadRunsOnConfiguredExecutor() {
        var threadName = new AtomicReference<String>();
        executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "payload-io-test"));
        var codec = new PayloadCodec(executor);
        var offloader = new InMemoryOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                threadName.set(Thread.currentThread().getName());
                return super.offload(serializedPayload, context);
            }
        };

        codec.serialize(new TestValue("stored"), new JacksonSerDes(), offloader, context());

        assertTrue(threadName.get().startsWith("payload-io-test"));
    }

    @Test
    void managedUserThreadRunsInlineWhenPayloadExecutorWrapsSameBackingPool() throws Exception {
        executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "shared-user-payload-test"));
        var wrappedPayloadExecutor = Executors.unconfigurableExecutorService(executor);
        var codec = new PayloadCodec(wrappedPayloadExecutor, () -> true);
        var offloadThread = new AtomicReference<String>();
        var offloader = new InMemoryOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                offloadThread.set(Thread.currentThread().getName());
                return super.offload(serializedPayload, context);
            }
        };

        var payload = executor.submit(
                        () -> codec.serialize(new TestValue("stored"), new JacksonSerDes(), offloader, context()))
                .get(5, TimeUnit.SECONDS);

        assertTrue(payload.startsWith(ENVELOPE_PREFIX));
        assertEquals("shared-user-payload-test", offloadThread.get());
    }

    @Test
    void malformedEnvelopeReportsPayloadIdentity() {
        var codec = codec();
        var error = assertThrows(
                PayloadOffloadException.class,
                () -> codec.deserialize(
                        "@aws-durable-payload:v1:not-json",
                        TypeToken.get(TestValue.class),
                        new JacksonSerDes(),
                        null,
                        context()));

        assertTrue(error.getMessage().contains("operation/op-1/result"));
    }

    @Test
    void envelopeWithTrailingJsonTokenFailsClosed() {
        var payload =
                codec().serialize(new TestValue("stored"), new JacksonSerDes(), new InMemoryOffloader(), context());

        assertThrows(PayloadOffloadException.class, () -> codec().deserialize(
                        payload + "{}",
                        TypeToken.get(TestValue.class),
                        new JacksonSerDes(),
                        new InMemoryOffloader(),
                        context()));
    }

    @Test
    void unsupportedEnvelopeVersionFailsClosed() {
        var codec = codec();

        var error = assertThrows(
                PayloadOffloadException.class,
                () -> codec.deserialize(
                        "@aws-durable-payload:v2:{}",
                        TypeToken.get(TestValue.class),
                        new JacksonSerDes(),
                        null,
                        context()));

        assertTrue(error.getMessage().contains("Unsupported or malformed"));
    }

    @Test
    void versionedEnvelopeWithoutProducerMetadataFailsClosed() {
        var codec = codec();
        var envelope = "@aws-durable-payload:v1:"
                + new JacksonSerDes().serialize(OffloadedPayload.inline("{\"value\":\"stored\"}"));

        var error = assertThrows(
                PayloadOffloadException.class,
                () -> codec.deserialize(
                        envelope, TypeToken.get(TestValue.class), new JacksonSerDes(), null, context()));

        assertTrue(error.getMessage().contains("missing producer ownership or integrity metadata"));
    }

    @Test
    void mismatchedExceptionEnvelopeOwnerIsRejected() {
        var codec = codec();
        var offloader = new InMemoryOffloader();
        var producer = PayloadOffloadContext.forOperation(
                context().durableExecutionArn(),
                OperationIdentifier.of("inner-step", "inner", OperationSubType.STEP),
                "child-context",
                SerDesPayloadKind.EXCEPTION,
                1);
        var consumer = PayloadOffloadContext.forOperation(
                context().durableExecutionArn(),
                OperationIdentifier.of("child-context", "child", OperationSubType.RUN_IN_CHILD_CONTEXT),
                null,
                SerDesPayloadKind.EXCEPTION,
                null);
        var payload =
                codec.serialize(new IllegalStateException("nested failure"), new JacksonSerDes(), offloader, producer);

        var error = assertThrows(
                PayloadOffloadException.class,
                () -> codec.deserialize(
                        payload, TypeToken.get(IllegalStateException.class), new JacksonSerDes(), offloader, consumer));

        assertTrue(error.getMessage().contains("different durable entity"));
    }

    @Test
    void forwardedExceptionPayloadIsReboundToTargetOffloader() {
        var codec = codec();
        var sourceOffloader = new InMemoryOffloader();
        var targetOffloader = new InMemoryOffloader();
        var producer = PayloadOffloadContext.forOperation(
                context().durableExecutionArn(),
                OperationIdentifier.of("inner-step", "inner", OperationSubType.STEP),
                "child-context",
                SerDesPayloadKind.EXCEPTION,
                1);
        var consumer = PayloadOffloadContext.forOperation(
                context().durableExecutionArn(),
                OperationIdentifier.of("child-context", "child", OperationSubType.RUN_IN_CHILD_CONTEXT),
                null,
                SerDesPayloadKind.EXCEPTION,
                null);
        var sourcePayload = codec.serialize(
                new IllegalStateException("nested failure"), new JacksonSerDes(), sourceOffloader, producer);

        var rebound =
                codec.rebindSerializedPayload(sourcePayload, sourceOffloader, producer, targetOffloader, consumer);
        var restored = codec.deserialize(
                rebound, TypeToken.get(IllegalStateException.class), new JacksonSerDes(), targetOffloader, consumer);

        assertEquals("nested failure", restored.getMessage());
        assertEquals(1, sourceOffloader.loadCount.get());
        assertEquals(1, targetOffloader.loadCount.get());
    }

    @Test
    void envelopeEncodingFailureIsClassifiedAsPayloadFailure() {
        var recursivePreview = new java.util.HashMap<String, Object>();
        recursivePreview.put("self", recursivePreview);
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.reference("memory://payload", recursivePreview);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                throw new AssertionError("load should not be called");
            }
        };

        var error = assertThrows(PayloadOffloadException.class, () -> codec().serialize(
                        new TestValue("stored"), new JacksonSerDes(), offloader, context()));

        assertTrue(error.getMessage().contains("Failed to encode payload offload envelope"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullablePreviewMetadataRoundTripsThroughEnvelope() {
        var preview = new LinkedHashMap<String, Object>();
        preview.put("nullable", null);
        var nested = new LinkedHashMap<String, Object>();
        nested.put("values", new ArrayList<>(java.util.Arrays.asList("present", null)));
        preview.put("nested", nested);
        var storedPayload = new AtomicReference<String>();
        var loadedPreview = new AtomicReference<Map<String, Object>>();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                storedPayload.set(serializedPayload);
                return OffloadedPayload.reference("memory://preview", preview);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadedPreview.set(payload.preview());
                return storedPayload.get();
            }
        };
        var writer = codec();
        var payload = writer.serialize(new TestValue("stored"), new JacksonSerDes(), offloader, context());
        writer.clear();

        var restored =
                codec().deserialize(payload, TypeToken.get(TestValue.class), new JacksonSerDes(), offloader, context());

        assertEquals("stored", restored.value());
        assertTrue(loadedPreview.get().containsKey("nullable"));
        assertNull(loadedPreview.get().get("nullable"));
        var restoredNested = (Map<?, ?>) loadedPreview.get().get("nested");
        var restoredValues = (List<?>) restoredNested.get("values");
        assertEquals("present", restoredValues.get(0));
        assertNull(restoredValues.get(1));
        assertThrows(
                UnsupportedOperationException.class, () -> loadedPreview.get().put("later", "value"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) restoredValues).add("later"));
    }

    private PayloadCodec codec() {
        executor = Executors.newCachedThreadPool();
        return new PayloadCodec(executor);
    }

    private static void assertInvalidEnvelope(String payload, PayloadOffloader offloader) {
        assertThrows(PayloadOffloadException.class, () -> new PayloadCodec(null)
                .deserialize(payload, TypeToken.get(String.class), new PassThroughSerDes(), offloader, context()));
    }

    private static String mutateEnvelope(String payload, Consumer<ObjectNode> mutation) {
        try {
            var envelope = (ObjectNode) TEST_MAPPER.readTree(payload.substring(ENVELOPE_PREFIX.length()));
            mutation.accept(envelope);
            return ENVELOPE_PREFIX + TEST_MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ObjectNode producerContext(ObjectNode envelope) {
        return (ObjectNode) envelope.get("producerContext");
    }

    private static PayloadOffloadContext context() {
        return context(1);
    }

    private static PayloadOffloadContext context(int attempt) {
        return PayloadOffloadContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/name/invocation",
                OperationIdentifier.of("op-1", "step", OperationSubType.STEP),
                null,
                SerDesPayloadKind.RESULT,
                attempt);
    }

    record TestValue(String value) {}

    private static final class FixedSerDes implements SerDes {
        private final String value;

        private FixedSerDes(String value) {
            this.value = value;
        }

        @Override
        public String serialize(Object value) {
            return "payload";
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return (T) new TestValue(value);
        }
    }

    private static final class PassThroughSerDes implements SerDes {
        @Override
        public String serialize(Object value) {
            return (String) value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return (T) data;
        }
    }

    private static class InMemoryOffloader implements PayloadOffloader {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();
        final AtomicInteger loadCount = new AtomicInteger();

        @Override
        public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
            var reference = "memory://" + sequence.incrementAndGet();
            values.put(reference, serializedPayload);
            return OffloadedPayload.reference(reference, null);
        }

        @Override
        public String load(OffloadedPayload payload, PayloadOffloadContext context) {
            loadCount.incrementAndGet();
            return values.get(payload.reference());
        }
    }
}
