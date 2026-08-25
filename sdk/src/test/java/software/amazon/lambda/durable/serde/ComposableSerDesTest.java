// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

class ComposableSerDesTest {

    @Test
    void serializesForwardAndDeserializesInReverse() {
        var calls = new ArrayList<String>();
        var first = stringStage("first", "<", ">", calls);
        var second = stringStage("second", "[", "]", calls);
        var pipeline = ComposableSerDes.of(new JacksonSerDes(), first.then(second));

        var serialized = pipeline.serialize("value");
        var deserialized = pipeline.deserialize(serialized, TypeToken.get(String.class));

        assertEquals("[<\"value\">]", serialized);
        assertEquals("value", deserialized);
        assertEquals(List.of("first-serialize", "second-serialize", "second-deserialize", "first-deserialize"), calls);
    }

    @Test
    void supportsTypedIntermediateValues() {
        var calls = new ArrayList<String>();
        SerDesStage<String, byte[]> utf8 = new SerDesStage<>() {
            @Override
            public byte[] serialize(String value) {
                calls.add("bytes-serialize");
                return value.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String deserialize(byte[] data) {
                calls.add("bytes-deserialize");
                return new String(data, StandardCharsets.UTF_8);
            }
        };
        SerDesStage<byte[], String> base64 = new SerDesStage<>() {
            @Override
            public String serialize(byte[] value) {
                calls.add("base64-serialize");
                return Base64.getEncoder().encodeToString(value);
            }

            @Override
            public byte[] deserialize(String data) {
                calls.add("base64-deserialize");
                return Base64.getDecoder().decode(data);
            }
        };
        var pipeline = ComposableSerDes.of(new JacksonSerDes(), utf8.then(base64));

        var serialized = pipeline.serialize("value");
        var deserialized = pipeline.deserialize(serialized, TypeToken.get(String.class));

        assertEquals(Base64.getEncoder().encodeToString("\"value\"".getBytes(StandardCharsets.UTF_8)), serialized);
        assertEquals("value", deserialized);
        assertEquals(List.of("bytes-serialize", "base64-serialize", "base64-deserialize", "bytes-deserialize"), calls);
    }

    @Test
    void factoryBuilderAndThenFlattenNestedStageChains() {
        var calls = new ArrayList<String>();
        var nested = stringStage("one", "1", "1", calls).then(stringStage("two", "2", "2", calls));
        var pipeline =
                ComposableSerDes.builder(new JacksonSerDes()).then(nested).build();

        assertEquals("21\"value\"12", pipeline.serialize("value"));
        assertEquals(List.of("one-serialize", "two-serialize"), calls);
    }

    @Test
    void nullBoundarySkipsEveryStage() {
        var calls = new AtomicInteger();
        var stage = new SerDes() {
            @Override
            public String serialize(Object value) {
                calls.incrementAndGet();
                return "unexpected";
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                return null;
            }
        };
        var pipeline = ComposableSerDes.of(stage);

        assertNull(pipeline.serialize(null));
        assertNull(pipeline.deserialize(null, TypeToken.get(String.class)));
        assertEquals(0, calls.get());
    }

    @Test
    void valueCodecMayDecodeNonNullRepresentationToNull() {
        var intermediateCalls = new AtomicInteger();
        var identityStage = new SerDesStage<String, String>() {
            @Override
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                intermediateCalls.incrementAndGet();
                return data;
            }
        };
        var pipeline = ComposableSerDes.of(new JacksonSerDes(), identityStage);

        assertNull(pipeline.deserialize("null", TypeToken.get(Object.class)));
        assertEquals(1, intermediateCalls.get());
    }

    @Test
    void stageMayDecodeExternalDataDirectlyWithValueCodec() {
        var transformDeserializations = new AtomicInteger();
        var transform = new SerDesStage<String, String>() {
            @Override
            public String serialize(String value) {
                return "<" + value + ">";
            }

            @Override
            public String deserialize(String data) {
                transformDeserializations.incrementAndGet();
                return data.substring(1, data.length() - 1);
            }
        };
        var externalBoundary = new SerDesStage<String, String>() {
            @Override
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                return data;
            }

            @Override
            public SerDesStageResult deserializePipelineStage(String data) {
                return SerDesStageResult.decodeWithValueCodec(data);
            }
        };
        var pipeline = ComposableSerDes.of(new JacksonSerDes(), transform.then(externalBoundary));

        assertEquals("value", pipeline.deserialize("\"value\"", TypeToken.get(String.class)));
        assertEquals(0, transformDeserializations.get());
    }

    @Test
    void rejectsStagesAfterTerminalStage() {
        var terminal = new SerDesStage<String, String>() {
            @Override
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                return data;
            }

            @Override
            public boolean isTerminalPipelineStage() {
                return true;
            }
        };

        var failure = assertThrows(
                IllegalArgumentException.class, () -> terminal.then(stringStage("late", "", "", new ArrayList<>())));

        assertTrue(failure.getMessage().contains("stage 1"));
        assertTrue(failure.getMessage().contains("final stage"));
    }

    @Test
    void rejectsNullIntermediateAndNonStringBoundaryValues() {
        var nullStage = new SerDesStage<String, String>() {
            @Override
            public String serialize(String value) {
                return null;
            }

            @Override
            public String deserialize(String data) {
                return null;
            }
        };
        var nullFailure = assertThrows(SerDesException.class, () -> ComposableSerDes.of(new JacksonSerDes(), nullStage)
                .serialize("value"));
        assertTrue(nullFailure.getMessage().contains("stage 1"));
        assertTrue(nullFailure.getMessage().contains(nullStage.getClass().getName()));

        SerDesStage<String, Integer> nonStringFinalStage = new SerDesStage<>() {
            @Override
            public Integer serialize(String value) {
                return value.length();
            }

            @Override
            public String deserialize(Integer data) {
                return "x".repeat(data);
            }
        };
        var typeFailure =
                assertThrows(SerDesException.class, () -> ComposableSerDes.of(new JacksonSerDes(), nonStringFinalStage)
                        .serialize("value"));
        assertTrue(typeFailure.getMessage().contains("final stage"));
        assertTrue(typeFailure.getMessage().contains(Integer.class.getName()));
    }

    @Test
    void incompatibleTypedStagesFailWithStageMetadata() {
        SerDesStage<Integer, String> integerStage = new SerDesStage<>() {
            @Override
            public String serialize(Integer value) {
                return value.toString();
            }

            @Override
            public Integer deserialize(String data) {
                return Integer.valueOf(data);
            }
        };

        var failure = assertThrows(SerDesException.class, () -> ComposableSerDes.of(new JacksonSerDes(), integerStage)
                .serialize("value"));

        assertTrue(failure.getMessage().contains("stage 1"));
        assertInstanceOf(ClassCastException.class, failure.getCause());
    }

    @Test
    void preservesRetryabilityWhenDecoratingStageFailures() {
        var transientStage = new SerDesStage<String, String>() {
            @Override
            public String serialize(String value) {
                throw new RetryableSerDesException("retry");
            }

            @Override
            public String deserialize(String data) {
                return null;
            }
        };

        var failure = assertThrows(
                RetryableSerDesException.class,
                () -> ComposableSerDes.of(new JacksonSerDes(), transientStage).serialize("value"));

        assertInstanceOf(RetryableSerDesException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("stage 1"));
    }

    @Test
    void preservesFatalErrorsFromEveryPipelineCall() {
        var serializeError = new OutOfMemoryError("serialize");
        var serializeStage = new SerDesStage<String, String>() {
            @Override
            public String serialize(String value) {
                throw serializeError;
            }

            @Override
            public String deserialize(String data) {
                return null;
            }
        };
        assertSame(serializeError, assertThrows(OutOfMemoryError.class, () -> ComposableSerDes.of(
                        new JacksonSerDes(), serializeStage)
                .serialize("value")));

        var stringStageError = new StackOverflowError("string-stage-deserialize");
        var stringStage = new SerDesStage<String, String>() {
            @Override
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                return null;
            }

            @Override
            public SerDesStageResult deserializePipelineStage(String data) {
                throw stringStageError;
            }
        };
        assertSame(stringStageError, assertThrows(StackOverflowError.class, () -> ComposableSerDes.of(
                        new JacksonSerDes(), stringStage)
                .deserialize("value", TypeToken.get(String.class))));

        var valueCodecError = new AssertionError("value-codec-deserialize");
        var valueCodec = new SerDes() {
            @Override
            public String serialize(Object value) {
                return value.toString();
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                throw valueCodecError;
            }
        };
        assertSame(valueCodecError, assertThrows(AssertionError.class, () -> ComposableSerDes.of(valueCodec)
                .deserialize("value", TypeToken.get(String.class))));
    }

    private static SerDesStage<String, String> stringStage(
            String name, String prefix, String suffix, List<String> calls) {
        return new SerDesStage<>() {
            @Override
            public String serialize(String value) {
                calls.add(name + "-serialize");
                return prefix + value + suffix;
            }

            @Override
            public String deserialize(String data) {
                calls.add(name + "-deserialize");
                return data.substring(prefix.length(), data.length() - suffix.length());
            }
        };
    }
}
