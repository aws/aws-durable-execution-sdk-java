// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void factoryBuilderAndThenFlattenNestedPipelines() {
        var calls = new ArrayList<String>();
        var nested = ComposableSerDes.builder(stringStage("codec", "", "", calls))
                .then(stringStage("one", "1", "1", calls))
                .build();
        var pipeline = ComposableSerDes.of(nested).then(stringStage("two", "2", "2", calls));

        assertEquals("21value12", pipeline.serialize("value"));
        assertEquals(List.of("codec-serialize", "one-serialize", "two-serialize"), calls);
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
    void rejectsNullAndNonStringIntermediateValuesWithStageMetadata() {
        var nullStage = new SerDes() {
            @Override
            public String serialize(Object value) {
                return null;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return null;
            }
        };
        var nullFailure = assertThrows(
                SerDesException.class, () -> new JacksonSerDes().then(nullStage).serialize("value"));
        assertTrue(nullFailure.getMessage().contains("stage 1"));
        assertTrue(nullFailure.getMessage().contains(nullStage.getClass().getName()));

        var nonStringStage = new SerDes() {
            @Override
            public String serialize(Object value) {
                return value.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return (T) Integer.valueOf(42);
            }
        };
        var typeFailure = assertThrows(
                SerDesException.class,
                () -> new JacksonSerDes().then(nonStringStage).deserialize("\"value\"", TypeToken.get(String.class)));
        assertTrue(typeFailure.getMessage().contains("stage 1"));
        assertTrue(typeFailure.getCause().getMessage().contains("non-string"));
    }

    @Test
    void preservesRetryabilityWhenDecoratingStageFailures() {
        var transientStage = new SerDes() {
            @Override
            public String serialize(Object value) {
                throw new RetryableSerDesException("retry");
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return null;
            }
        };

        var failure = assertThrows(
                RetryableSerDesException.class,
                () -> new JacksonSerDes().then(transientStage).serialize("value"));

        assertInstanceOf(RetryableSerDesException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("stage 1"));
    }

    private static SerDes stringStage(String name, String prefix, String suffix, List<String> calls) {
        return new SerDes() {
            @Override
            public String serialize(Object value) {
                calls.add(name + "-serialize");
                return prefix + value + suffix;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.add(name + "-deserialize");
                return (T) data.substring(prefix.length(), data.length() - suffix.length());
            }
        };
    }
}
