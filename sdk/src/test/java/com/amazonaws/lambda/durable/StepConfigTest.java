// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.amazonaws.lambda.durable.retry.RetryStrategies;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import org.junit.jupiter.api.Test;

class StepConfigTest {

    @Test
    void testBuilderWithRetryStrategy() {
        var strategy = RetryStrategies.Presets.DEFAULT;

        var config = StepConfig.builder().retryStrategy(strategy).build();

        assertEquals(strategy, config.retryStrategy());
    }

    @Test
    void testBuilderWithoutRetryStrategy() {
        var config = StepConfig.builder().build();

        assertNull(config.retryStrategy());
    }

    @Test
    void testBuilderChaining() {
        var strategy = RetryStrategies.Presets.NO_RETRY;
        SerDes customSerDes = new JacksonSerDes();

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

        assertNull(config.retryStrategy());
    }

    @Test
    void testSemanticsDefaultsToAtLeastOnce() {
        var config = StepConfig.builder().build();

        assertEquals(StepSemantics.AT_LEAST_ONCE_PER_RETRY, config.semantics());
    }

    @Test
    void testBuilderWithCustomSerDes() {
        SerDes customSerDes = new JacksonSerDes();

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
        SerDes customSerDes = new JacksonSerDes();

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
