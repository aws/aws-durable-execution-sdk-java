// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class StepConfigTest {

    @Test
    void testBuilderWithRetryStrategy() {
        var strategy = RetryStrategies.Presets.NO_RETRY;

        var config = StepConfig.builder().retryStrategy(strategy).build();

        assertEquals(strategy, config.retryStrategy());
    }

    @Test
    void testBuilderWithoutRetryStrategy() {
        var config = StepConfig.builder().build();

        assertEquals(RetryStrategies.Presets.DEFAULT, config.retryStrategy());
    }

    @Test
    void testBuilderChaining() {
        var strategy = RetryStrategies.Presets.DEFAULT;
        var customSerDes = new JacksonSerDes();

        var config = StepConfig.builder()
                .retryStrategy(strategy)
                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .serDes(customSerDes)
                .build();

        assertEquals(strategy, config.retryStrategy());
        assertEquals(StepSemantics.AT_MOST_ONCE_PER_RETRY, config.semantics());
        assertEquals(customSerDes, config.serDes());
    }

    @Test
    void testBuilderWithNullRetryStrategy() {
        var config = StepConfig.builder().retryStrategy(null).build();

        assertEquals(RetryStrategies.Presets.DEFAULT, config.retryStrategy());
    }

    @Test
    void testSemanticsDefaultsToAtLeastOnce() {
        var config = StepConfig.builder().build();

        assertEquals(StepSemantics.AT_LEAST_ONCE_PER_RETRY, config.semantics());
    }

    @Test
    void testBuilderWithCustomSerDes() {
        var customSerDes = new JacksonSerDes();

        var config = StepConfig.builder().serDes(customSerDes).build();

        assertNotNull(config.serDes());
        assertEquals(customSerDes, config.serDes());
    }

    @Test
    void testBuilderWithoutCustomSerDes() {
        var config = StepConfig.builder().build();

        assertNull(config.serDes());
    }

    @Test
    void testBuilderWithNullSerDes() {
        var config = StepConfig.builder().serDes(null).build();

        assertNull(config.serDes());
    }

    @Test
    void testBuilderWithAllOptions() {
        var strategy = RetryStrategies.Presets.DEFAULT;
        var customSerDes = new JacksonSerDes();

        var config = StepConfig.builder()
                .retryStrategy(strategy)
                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .serDes(customSerDes)
                .build();

        assertEquals(strategy, config.retryStrategy());
        assertEquals(StepSemantics.AT_MOST_ONCE_PER_RETRY, config.semantics());
        assertEquals(customSerDes, config.serDes());
    }
}
