// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

class ComposableSerDesTest {

    @Test
    void onlyAcceptsStringStagesAfterTheValueCodec() throws Exception {
        assertThrows(NoSuchMethodException.class, () -> SerDes.class.getMethod("then", SerDes.class));
        assertEquals(
                ComposableSerDes.class,
                SerDes.class.getMethod("then", SerDesStage.class).getReturnType());
    }

    @Test
    void serializesForwardAndDeserializesInReverse() {
        var calls = new ArrayList<String>();
        var first = stringStage("first", "<", ">", calls);
        var second = stringStage("second", "[", "]", calls);
        var pipeline = new JacksonSerDes().then(first).then(second);

        var serialized = pipeline.serialize("value");
        var deserialized = pipeline.deserialize(serialized, TypeToken.get(String.class));

        assertEquals("[<\"value\">]", serialized);
        assertEquals("value", deserialized);
        assertEquals(List.of("first-serialize", "second-serialize", "second-deserialize", "first-deserialize"), calls);
    }

    @Test
    void supportsDedicatedStringStages() {
        var calls = new ArrayList<String>();
        var first = new SerDesStage() {
            @Override
            public String serialize(String value) {
                calls.add("first-serialize");
                return "<" + value + ">";
            }

            @Override
            public String deserialize(String data) {
                calls.add("first-deserialize");
                return data.substring(1, data.length() - 1);
            }
        };
        var second = new SerDesStage() {
            @Override
            public String serialize(String value) {
                calls.add("second-serialize");
                return "[" + value + "]";
            }

            @Override
            public String deserialize(String data) {
                calls.add("second-deserialize");
                return data.substring(1, data.length() - 1);
            }
        };
        var pipeline = new JacksonSerDes().then(first).then(second);

        var serialized = pipeline.serialize("value");
        var deserialized = pipeline.deserialize(serialized, TypeToken.get(String.class));

        assertEquals("[<\"value\">]", serialized);
        assertEquals("value", deserialized);
        assertEquals(List.of("first-serialize", "second-serialize", "second-deserialize", "first-deserialize"), calls);
    }

    @Test
    void factoryBuilderAndThenFlattenRootPipeline() {
        var calls = new ArrayList<String>();
        var nested = ComposableSerDes.builder(new JacksonSerDes())
                .then(stringStage("one", "1", "1", calls))
                .build();
        var pipeline = ComposableSerDes.of(nested).then(stringStage("two", "2", "2", calls));

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
        var identityStage = new SerDesStage() {
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
        var pipeline = new JacksonSerDes().then(identityStage);

        assertNull(pipeline.deserialize("null", TypeToken.get(Object.class)));
        assertEquals(1, intermediateCalls.get());
    }

    @Test
    void unrecognizedInputPassesThroughEveryStage() {
        var calls = new ArrayList<String>();
        var pipeline = new JacksonSerDes()
                .then(stringStage("first", "<", ">", calls))
                .then(stringStage("second", "[", "]", calls));

        assertEquals("value", pipeline.deserialize("\"value\"", TypeToken.get(String.class)));
        assertEquals(List.of("second-deserialize", "first-deserialize"), calls);
    }

    @Test
    void recognizedMalformedInputFailsAtTheOwningStage() {
        var pipeline = new JacksonSerDes().then(stringStage("framed", "<", ">", new ArrayList<>()));

        var failure = assertThrows(
                SerDesException.class, () -> pipeline.deserialize("<\"value\"", TypeToken.get(String.class)));

        assertTrue(failure.getMessage().contains("stage 1"));
        assertTrue(failure.getCause().getMessage().contains("Malformed framed stage value"));
    }

    @Test
    void rejectsNullIntermediateValues() {
        var nullStage = new SerDesStage() {
            @Override
            public String serialize(String value) {
                return null;
            }

            @Override
            public String deserialize(String data) {
                return null;
            }
        };
        var nullFailure = assertThrows(
                SerDesException.class, () -> new JacksonSerDes().then(nullStage).serialize("value"));
        assertTrue(nullFailure.getMessage().contains("stage 1"));
        assertTrue(nullFailure.getMessage().contains(nullStage.getClass().getName()));
    }

    @Test
    void preservesRetryabilityWhenDecoratingStageFailures() {
        var transientStage = new SerDesStage() {
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
                () -> new JacksonSerDes().then(transientStage).serialize("value"));

        assertInstanceOf(RetryableSerDesException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("stage 1"));
    }

    @Test
    void preservesFatalErrorsFromEveryPipelineCall() {
        var serializeError = new OutOfMemoryError("serialize");
        var serializeStage = new SerDesStage() {
            @Override
            public String serialize(String value) {
                throw serializeError;
            }

            @Override
            public String deserialize(String data) {
                return null;
            }
        };
        assertSame(serializeError, assertThrows(OutOfMemoryError.class, () -> new JacksonSerDes()
                .then(serializeStage)
                .serialize("value")));

        var stringStageError = new StackOverflowError("string-stage-deserialize");
        var stringStage = new SerDesStage() {
            @Override
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                throw stringStageError;
            }
        };
        assertSame(stringStageError, assertThrows(StackOverflowError.class, () -> new JacksonSerDes()
                .then(stringStage)
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

    private static SerDesStage stringStage(String name, String prefix, String suffix, List<String> calls) {
        return new SerDesStage() {
            @Override
            public String serialize(String value) {
                calls.add(name + "-serialize");
                return prefix + value + suffix;
            }

            @Override
            public String deserialize(String data) {
                calls.add(name + "-deserialize");
                if (!data.startsWith(prefix)) {
                    return data;
                }
                if (!data.endsWith(suffix) || data.length() < prefix.length() + suffix.length()) {
                    throw new SerDesException("Malformed " + name + " stage value");
                }
                return data.substring(prefix.length(), data.length() - suffix.length());
            }
        };
    }
}
