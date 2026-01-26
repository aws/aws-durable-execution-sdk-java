// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.lambda.durable.client.LambdaDurableFunctionsClient;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

class CustomConfigIntegrationTest {

    /** Custom SerDes that tracks serialization calls for testing precedence. */
    static class TrackingSerDes implements SerDes {
        private final JacksonSerDes delegate = new JacksonSerDes();
        private final AtomicInteger serializeCount = new AtomicInteger(0);
        private final AtomicInteger deserializeCount = new AtomicInteger(0);
        private final String name;

        public TrackingSerDes(String name) {
            this.name = name;
        }

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

        public String getName() {
            return name;
        }
    }

    @Test
    void testCustomSerDes_Integration() {
        // Create custom SerDes for testing
        var customSerDes = new TrackingSerDes("Custom");

        // Create custom config with the custom SerDes
        var customConfig = DurableConfig.builder().withSerDes(customSerDes).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    return context.step("process", String.class, () -> "Custom SerDes: " + input);
                },
                customConfig);

        var result = runner.run("test-input");

        assertNotNull(result);
        assertEquals("Custom SerDes: test-input", result.getResult(String.class));

        // Verify custom SerDes was actually used
        assertTrue(customSerDes.getSerializeCount() > 0, "Custom SerDes should have been used for serialization");
    }

    @Test
    void testDefaultConfig_WorksCorrectly() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            return context.step("process", String.class, () -> "Default: " + input);
        });

        var result = runner.run("test-input");

        assertNotNull(result);
        assertEquals("Default: test-input", result.getResult(String.class));
    }

    @Test
    void testCustomConfig_WithMultipleSteps() {
        // Create custom SerDes for testing
        var customSerDes = new TrackingSerDes("MultiStep");

        // Create custom config
        var customConfig = DurableConfig.builder().withSerDes(customSerDes).build();

        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> {
                            var step1 = context.step("step1", String.class, () -> "Step1: " + input);

                            // Remove wait operation to avoid complexity in this test
                            var step2 = context.step("step2", String.class, () -> "Step2: " + input);

                            var step3 = context.step("step3", String.class, () -> "Step3: " + input);

                            return step1 + " | " + step2 + " | " + step3;
                        },
                        customConfig)
                .withSkipTime(true);

        var result = runner.run("test");

        assertNotNull(result);
        assertEquals("Step1: test | Step2: test | Step3: test", result.getResult(String.class));

        // Verify all steps executed
        assertNotNull(result.getOperation("step1"));
        assertNotNull(result.getOperation("step2"));
        assertNotNull(result.getOperation("step3"));

        // Verify custom SerDes was used (should be called multiple times for multiple steps)
        assertTrue(customSerDes.getSerializeCount() >= 3, "Custom SerDes should have been used for all steps");
    }

    @Test
    void testCustomConfig_WithRetry() {
        var attemptCount = new AtomicInteger(0);

        // Create custom SerDes for testing
        var customSerDes = new TrackingSerDes("Retry");

        // Create custom config
        var customConfig = DurableConfig.builder().withSerDes(customSerDes).build();

        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> {
                            return context.step(
                                    "retry-step",
                                    String.class,
                                    () -> {
                                        int currentAttempt = attemptCount.incrementAndGet();
                                        // Always fail to test retry behavior (like existing RetryIntegrationTest)
                                        throw new RuntimeException("Simulated failure attempt " + currentAttempt);
                                    },
                                    StepConfig.builder()
                                            .retryStrategy(
                                                    com.amazonaws.lambda.durable.retry.RetryStrategies.Presets.DEFAULT)
                                            .build());
                        },
                        customConfig)
                .withSkipTime(true);

        // First run should return PENDING (retry scheduled) - matching existing RetryIntegrationTest pattern
        var result = runner.run("test");
        assertEquals(com.amazonaws.lambda.durable.model.ExecutionStatus.PENDING, result.getStatus());
        assertEquals(1, attemptCount.get());

        // Verify custom SerDes was used during retry operations
        assertTrue(customSerDes.getSerializeCount() > 0, "Custom SerDes should have been used during retry operations");
    }

    @Test
    void testConfigImmutability_AcrossInvocations() {
        // Create separate custom configs for each runner to test immutability
        var customSerDes1 = new TrackingSerDes("Config1");
        var customSerDes2 = new TrackingSerDes("Config2");
        var customSerDes3 = new TrackingSerDes("Config3");

        var customConfig1 = DurableConfig.builder().withSerDes(customSerDes1).build();
        var customConfig2 = DurableConfig.builder().withSerDes(customSerDes2).build();
        var customConfig3 = DurableConfig.builder().withSerDes(customSerDes3).build();

        // Create separate runners for each invocation to avoid state reuse
        var runner1 = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    return context.step("process", String.class, () -> "Config1: " + input);
                },
                customConfig1);

        var runner2 = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    return context.step("process", String.class, () -> "Config2: " + input);
                },
                customConfig2);

        var runner3 = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    return context.step("process", String.class, () -> "Config3: " + input);
                },
                customConfig3);

        // Run with different inputs using separate runners
        var result1 = runner1.run("input1");
        var result2 = runner2.run("input2");
        var result3 = runner3.run("input3");

        assertEquals("Config1: input1", result1.getResult(String.class));
        assertEquals("Config2: input2", result2.getResult(String.class));
        assertEquals("Config3: input3", result3.getResult(String.class));

        // Verify each custom SerDes was used independently
        assertTrue(customSerDes1.getSerializeCount() > 0, "Custom SerDes 1 should have been used");
        assertTrue(customSerDes2.getSerializeCount() > 0, "Custom SerDes 2 should have been used");
        assertTrue(customSerDes3.getSerializeCount() > 0, "Custom SerDes 3 should have been used");
    }

    @Test
    void testDurableConfig_DefaultRegionFallback() {
        // Test that DurableConfig.defaultConfig() works even without AWS_REGION
        // This verifies the DEFAULT_REGION fallback behavior
        var config = DurableConfig.defaultConfig();

        assertNotNull(config);
        assertNotNull(config.getDurableExecutionClient());
        assertNotNull(config.getSerDes());
        assertNotNull(config.getExecutorService());
    }

    @Test
    void testStepConfigSerDesPrecedenceOverDurableConfig() {
        // Create tracking SerDes to verify which one is actually used
        var durableConfigSerDes = new TrackingSerDes("DurableConfig");
        var stepConfigSerDes = new TrackingSerDes("StepConfig");

        // Create custom config with DurableConfig SerDes
        var customConfig =
                DurableConfig.builder().withSerDes(durableConfigSerDes).build();

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    // Step 1: Use default SerDes (should use DurableConfig SerDes)
                    var result1 = context.step("default-step", String.class, () -> "default-result");

                    // Step 2: Use StepConfig SerDes (should override DurableConfig SerDes)
                    var result2 = context.step(
                            "custom-step",
                            String.class,
                            () -> "custom-result",
                            StepConfig.builder().serDes(stepConfigSerDes).build());

                    return result1 + "," + result2;
                },
                customConfig);

        var result = runner.run("test-input");

        assertNotNull(result);
        assertEquals("default-result,custom-result", result.getResult(String.class));

        // Verify DurableConfig SerDes was used for the default step. Can be called more than once also for customer
        // input deserialization from within LocalDurableTestRunner.
        assertTrue(
                durableConfigSerDes.getSerializeCount() > 0,
                "DurableConfig SerDes should have been used for default step serialization");

        // Verify StepConfig SerDes was used for the custom step
        assertTrue(
                stepConfigSerDes.getSerializeCount() == 1,
                "StepConfig SerDes should have been used for custom step serialization");
    }

    @Test
    void testCustomDurableExecutionClient_Configuration() {
        // Test creating custom DurableExecutionClient with LambdaDurableFunctionsClient
        var lambdaClient = LambdaClient.builder().region(Region.US_WEST_2).build();

        try {
            var durableClient = new LambdaDurableFunctionsClient(lambdaClient);

            var config = DurableConfig.builder()
                    .withDurableExecutionClient(durableClient)
                    .build();

            assertNotNull(config);
            assertEquals(durableClient, config.getDurableExecutionClient());
        } finally {
            // Clean up lambda client
            lambdaClient.close();
        }
    }
}
