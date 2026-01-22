// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Integration tests for custom SerDes configuration in CallbackConfig. */
class CustomSerDesCallbackIntegrationTest {

    /** Custom SerDes that tracks serialization and deserialization calls. */
    static class TrackingSerDes implements SerDes {
        private final JacksonSerDes delegate = new JacksonSerDes();
        private final AtomicInteger serializeCount = new AtomicInteger(0);
        private final AtomicInteger deserializeCount = new AtomicInteger(0);

        @Override
        public String serialize(Object value) {
            serializeCount.incrementAndGet();
            return delegate.serialize(value);
        }

        @Override
        public <T> T deserialize(String data, Class<T> type) {
            deserializeCount.incrementAndGet();
            return delegate.deserialize(data, type);
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            deserializeCount.incrementAndGet();
            return delegate.deserialize(data, typeToken);
        }

        public int getSerializeCount() {
            return serializeCount.get();
        }

        public int getDeserializeCount() {
            return deserializeCount.get();
        }
    }

    @Test
    void testCustomSerDesIsUsedForCallbackDeserialization() {
        var customSerDes = new TrackingSerDes();

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Create callback with custom SerDes
            var cb = ctx.createCallback(
                    "approval",
                    String.class,
                    CallbackConfig.builder().serDes(customSerDes).build());

            // Get result - should use custom SerDes for deserialization
            return cb.future().get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test-input");

        // Complete the callback
        var callbackId = runner.getCallbackId("approval");
        runner.completeCallback(callbackId, "\"approved\"");

        // Second run - callback complete, returns result
        result = runner.run("test-input");

        assertEquals("approved", result.getResult(String.class));
        // Custom SerDes should have been used for deserialization
        assertTrue(customSerDes.getDeserializeCount() > 0, "Custom SerDes should have been used for deserialization");
    }

    @Test
    void testDefaultSerDesWhenNotSpecified() {
        var customSerDes = new TrackingSerDes();

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Create callback without custom SerDes
            var cb = ctx.createCallback("approval", String.class);
            return cb.future().get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test-input");

        // Complete the callback
        var callbackId = runner.getCallbackId("approval");
        runner.completeCallback(callbackId, "\"approved\"");

        // Second run - callback complete, returns result
        result = runner.run("test-input");

        assertEquals("approved", result.getResult(String.class));
        // Custom SerDes should NOT have been used
        assertEquals(0, customSerDes.getDeserializeCount(), "Custom SerDes should not have been used");
    }

    @Test
    void testMixedSerDesUsage() {
        var customSerDes1 = new TrackingSerDes();
        var customSerDes2 = new TrackingSerDes();

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Callback 1: Default SerDes
            var cb1 = ctx.createCallback("approval1", String.class);

            // Callback 2: Custom SerDes 1
            var cb2 = ctx.createCallback(
                    "approval2",
                    String.class,
                    CallbackConfig.builder().serDes(customSerDes1).build());

            // Callback 3: Custom SerDes 2
            var cb3 = ctx.createCallback(
                    "approval3",
                    String.class,
                    CallbackConfig.builder().serDes(customSerDes2).build());

            var result1 = cb1.future().get();
            var result2 = cb2.future().get();
            var result3 = cb3.future().get();

            return result1 + "," + result2 + "," + result3;
        });

        // First run - creates all callbacks, suspends on first
        var result = runner.run("test-input");

        // Complete all callbacks
        runner.completeCallback(runner.getCallbackId("approval1"), "\"default\"");
        runner.completeCallback(runner.getCallbackId("approval2"), "\"custom1\"");
        runner.completeCallback(runner.getCallbackId("approval3"), "\"custom2\"");

        // Run until complete
        result = runner.runUntilComplete("test-input");

        assertEquals("default,custom1,custom2", result.getResult(String.class));
        // Each custom SerDes should have been used exactly once
        assertTrue(customSerDes1.getDeserializeCount() > 0, "Custom SerDes 1 should have been used");
        assertTrue(customSerDes2.getDeserializeCount() > 0, "Custom SerDes 2 should have been used");
    }

    @Test
    void testNullSerDesUsesDefault() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Explicitly pass null SerDes - should use default
            var cb = ctx.createCallback(
                    "approval",
                    String.class,
                    CallbackConfig.builder().serDes(null).build());

            return cb.future().get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test-input");

        // Complete the callback
        var callbackId = runner.getCallbackId("approval");
        runner.completeCallback(callbackId, "\"result\"");

        // Second run - callback complete, returns result
        result = runner.run("test-input");

        assertEquals("result", result.getResult(String.class));
    }
}
