// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.lambda.durable.exception.SerDesException;
import org.junit.jupiter.api.Test;

class JacksonSerDesTest {

    record TestData(String name, int value) {}

    @Test
    void testRoundTrip() {
        var serDes = new JacksonSerDes();
        var original = new TestData("test", 42);

        var json = serDes.serialize(original);
        var deserialized = serDes.deserialize(json, TestData.class);

        assertEquals(original, deserialized);
    }

    @Test
    void testNullHandling() {
        var serDes = new JacksonSerDes();

        assertNull(serDes.serialize(null));
        assertNull(serDes.deserialize(null, String.class));
    }

    @Test
    void testDeserializationThrowsSerDesException() {
        var serDes = new JacksonSerDes();
        var invalidJson = "{invalid json}";

        var exception = assertThrows(SerDesException.class, () -> {
            serDes.deserialize(invalidJson, TestData.class);
        });

        assertTrue(exception.getMessage().contains("Deserialization failed"));
        assertTrue(exception.getMessage().contains("TestData"));
        assertNotNull(exception.getCause());
    }

    @Test
    void testSerializationThrowsSerDesException() {
        var serDes = new JacksonSerDes();

        // Create an object that cannot be serialized (circular reference)
        class CircularReference {
            CircularReference self;

            CircularReference() {
                this.self = this;
            }
        }

        var circular = new CircularReference();

        var exception = assertThrows(SerDesException.class, () -> {
            serDes.serialize(circular);
        });

        assertTrue(exception.getMessage().contains("Serialization failed"));
        assertTrue(exception.getMessage().contains("CircularReference"));
        assertNotNull(exception.getCause());
    }

    @Test
    void testSerDesExceptionExtendsRuntimeException() {
        var serDes = new JacksonSerDes();
        var invalidJson = "not json";

        // Verify SerDesException is a RuntimeException (unchecked)
        assertThrows(RuntimeException.class, () -> {
            serDes.deserialize(invalidJson, TestData.class);
        });
    }
}
