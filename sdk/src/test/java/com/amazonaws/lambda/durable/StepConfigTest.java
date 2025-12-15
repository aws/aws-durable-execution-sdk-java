package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.amazonaws.lambda.durable.retry.RetryStrategies;
import com.amazonaws.lambda.durable.retry.RetryStrategy;

class StepConfigTest {

    @Test
    void testBuilderWithRetryStrategy() {
        RetryStrategy strategy = RetryStrategies.Presets.DEFAULT;

        StepConfig config = StepConfig.builder()
                .retryStrategy(strategy)
                .build();

        assertEquals(strategy, config.retryStrategy());
    }

    @Test
    void testBuilderWithoutRetryStrategy() {
        StepConfig config = StepConfig.builder().build();

        assertNull(config.retryStrategy());
    }

    @Test
    void testBuilderChaining() {
        RetryStrategy strategy = RetryStrategies.Presets.NO_RETRY;

        StepConfig config = StepConfig.builder()
                .retryStrategy(strategy)
                // TODO: Add more chaining here once implemented
                .build();

        assertEquals(strategy, config.retryStrategy());
    }

    @Test
    void testBuilderWithNullRetryStrategy() {
        StepConfig config = StepConfig.builder()
                .retryStrategy(null)
                .build();

        assertNull(config.retryStrategy());
    }
}
