// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class WaitForCallbackConfigTest {

    @Test
    void testBuilderWithDefaultValues() {
        var config = WaitForCallbackConfig.builder().build();

        assertNotNull(config.stepConfig());
        assertNotNull(config.callbackConfig());
    }

    @Test
    void testBuilderWithCustomStepConfig() {
        var stepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();

        var config = WaitForCallbackConfig.builder().stepConfig(stepConfig).build();

        assertEquals(stepConfig, config.stepConfig());
        assertNotNull(config.callbackConfig());
    }

    @Test
    void testBuilderWithCustomCallbackConfig() {
        var callbackConfig =
                CallbackConfig.builder().timeout(Duration.ofMinutes(10)).build();

        var config =
                WaitForCallbackConfig.builder().callbackConfig(callbackConfig).build();

        assertNotNull(config.stepConfig());
        assertEquals(callbackConfig, config.callbackConfig());
    }

    @Test
    void testBuilderWithBothConfigs() {
        var stepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .build();
        var callbackConfig = CallbackConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .heartbeatTimeout(Duration.ofSeconds(30))
                .build();

        var config = WaitForCallbackConfig.builder()
                .stepConfig(stepConfig)
                .callbackConfig(callbackConfig)
                .build();

        assertEquals(stepConfig, config.stepConfig());
        assertEquals(callbackConfig, config.callbackConfig());
    }

    @Test
    void testBuilderWithNullStepConfig() {
        var config = WaitForCallbackConfig.builder().stepConfig(null).build();

        assertNotNull(config.stepConfig());
        assertNotNull(config.callbackConfig());
    }

    @Test
    void testBuilderWithNullCallbackConfig() {
        var config = WaitForCallbackConfig.builder().callbackConfig(null).build();

        assertNotNull(config.stepConfig());
        assertNotNull(config.callbackConfig());
    }

    @Test
    void testBuilderChaining() {
        var stepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                .build();
        var callbackConfig =
                CallbackConfig.builder().timeout(Duration.ofMinutes(15)).build();

        var config = WaitForCallbackConfig.builder()
                .stepConfig(stepConfig)
                .callbackConfig(callbackConfig)
                .build();

        assertEquals(stepConfig, config.stepConfig());
        assertEquals(callbackConfig, config.callbackConfig());
    }

    @Test
    void testToBuilder() {
        var stepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        var callbackConfig =
                CallbackConfig.builder().timeout(Duration.ofMinutes(20)).build();

        var originalConfig = WaitForCallbackConfig.builder()
                .stepConfig(stepConfig)
                .callbackConfig(callbackConfig)
                .build();

        var newConfig = originalConfig.toBuilder().build();

        assertEquals(originalConfig.stepConfig(), newConfig.stepConfig());
        assertEquals(originalConfig.callbackConfig(), newConfig.callbackConfig());
    }

    @Test
    void testToBuilderWithModifications() {
        var originalStepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                .build();
        var originalCallbackConfig =
                CallbackConfig.builder().timeout(Duration.ofMinutes(5)).build();

        var originalConfig = WaitForCallbackConfig.builder()
                .stepConfig(originalStepConfig)
                .callbackConfig(originalCallbackConfig)
                .build();

        var newStepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();

        var modifiedConfig =
                originalConfig.toBuilder().stepConfig(newStepConfig).build();

        assertEquals(newStepConfig, modifiedConfig.stepConfig());
        assertEquals(originalCallbackConfig, modifiedConfig.callbackConfig());
    }

    @Test
    void testBuilderWithAllOptions() {
        var customSerDes = new JacksonSerDes();
        var stepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .serDes(customSerDes)
                .build();
        var callbackConfig = CallbackConfig.builder()
                .timeout(Duration.ofMinutes(10))
                .heartbeatTimeout(Duration.ofMinutes(2))
                .serDes(customSerDes)
                .build();

        var config = WaitForCallbackConfig.builder()
                .stepConfig(stepConfig)
                .callbackConfig(callbackConfig)
                .build();

        assertEquals(stepConfig, config.stepConfig());
        assertEquals(callbackConfig, config.callbackConfig());
        assertEquals(RetryStrategies.Presets.DEFAULT, config.stepConfig().retryStrategy());
        assertEquals(StepSemantics.AT_MOST_ONCE_PER_RETRY, config.stepConfig().semantics());
        assertEquals(Duration.ofMinutes(10), config.callbackConfig().timeout());
        assertEquals(Duration.ofMinutes(2), config.callbackConfig().heartbeatTimeout());
    }

    @Test
    void testStepConfigDefaultsWhenNull() {
        var config = WaitForCallbackConfig.builder().stepConfig(null).build();

        assertNotNull(config.stepConfig());
        assertEquals(RetryStrategies.Presets.DEFAULT, config.stepConfig().retryStrategy());
        assertEquals(StepSemantics.AT_LEAST_ONCE_PER_RETRY, config.stepConfig().semantics());
        assertNull(config.stepConfig().serDes());
    }

    @Test
    void testCallbackConfigDefaultsWhenNull() {
        var config = WaitForCallbackConfig.builder().callbackConfig(null).build();

        assertNotNull(config.callbackConfig());
        assertNull(config.callbackConfig().timeout());
        assertNull(config.callbackConfig().heartbeatTimeout());
        assertNull(config.callbackConfig().serDes());
    }
}
