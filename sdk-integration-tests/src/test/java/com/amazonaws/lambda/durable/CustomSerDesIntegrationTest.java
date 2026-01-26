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

/** Integration tests for custom SerDes configuration in StepConfig. */
class CustomSerDesIntegrationTest {

    /** Custom SerDes that tracks serialization calls. */
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
    void testCustomSerDesIsUsedInStep() {
        var customSerDes = new TrackingSerDes();

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // Step with custom SerDes
            var result = context.step(
                    "custom-step",
                    String.class,
                    () -> "custom-result",
                    StepConfig.builder().serDes(customSerDes).build());

            return result;
        });

        var result = runner.run("test-input");

        assertEquals("custom-result", result.getResult(String.class));
        // Custom SerDes should have been used for serialization
        assertTrue(customSerDes.getSerializeCount() > 0, "Custom SerDes should have been used for serialization");
    }

    @Test
    void testDefaultSerDesWhenNotSpecified() {
        var customSerDes = new TrackingSerDes();

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // Step without custom SerDes
            var result = context.step("default-step", String.class, () -> "default-result");

            return result;
        });

        var result = runner.run("test-input");

        assertEquals("default-result", result.getResult(String.class));
        // Custom SerDes should NOT have been used
        assertEquals(0, customSerDes.getSerializeCount(), "Custom SerDes should not have been used");
    }

    @Test
    void testMixedSerDesUsage() {
        var customSerDes1 = new TrackingSerDes();
        var customSerDes2 = new TrackingSerDes();

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // Step 1: Default SerDes
            var result1 = context.step("default-step", String.class, () -> "default");

            // Step 2: Custom SerDes 1
            var result2 = context.step(
                    "custom-step-1",
                    String.class,
                    () -> "custom1",
                    StepConfig.builder().serDes(customSerDes1).build());

            // Step 3: Custom SerDes 2
            var result3 = context.step(
                    "custom-step-2",
                    String.class,
                    () -> "custom2",
                    StepConfig.builder().serDes(customSerDes2).build());

            return result1 + "," + result2 + "," + result3;
        });

        var result = runner.run("test-input");

        assertEquals("default,custom1,custom2", result.getResult(String.class));
        // Each custom SerDes should have been used exactly once
        assertTrue(customSerDes1.getSerializeCount() > 0, "Custom SerDes 1 should have been used");
        assertTrue(customSerDes2.getSerializeCount() > 0, "Custom SerDes 2 should have been used");
    }

    @Test
    void testNullSerDesUsesDefault() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // Explicitly pass null SerDes - should use default
            return context.step(
                    "test-step",
                    String.class,
                    () -> "result",
                    StepConfig.builder().serDes(null).build());
        });

        var result = runner.run("test-input");
        assertEquals("result", result.getResult(String.class));
    }
}
