// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;

class RetryOperationConfigTest {

    @Test
    void builderWithRetryStrategy() {
        var strategy = RetryStrategies.Presets.DEFAULT;

        var config = RetryOperationConfig.builder().retryStrategy(strategy).build();

        assertEquals(strategy, config.retryStrategy());
    }

    @Test
    void builderWithoutRetryStrategy_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> RetryOperationConfig.builder()
                .build());

        assertEquals("retryStrategy is required", exception.getMessage());
    }

    @Test
    void wrapInChildContext_defaultsToTrue() {
        var config = RetryOperationConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();

        assertTrue(config.wrapInChildContext());
    }

    @Test
    void wrapInChildContext_canBeSetToFalse() {
        var config = RetryOperationConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .wrapInChildContext(false)
                .build();

        assertFalse(config.wrapInChildContext());
    }

    @Test
    void wrapInChildContext_canBeSetToTrue() {
        var config = RetryOperationConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .wrapInChildContext(true)
                .build();

        assertTrue(config.wrapInChildContext());
    }

    @Test
    void builderChaining() {
        var strategy = RetryStrategies.Presets.DEFAULT;

        var config = RetryOperationConfig.builder()
                .retryStrategy(strategy)
                .wrapInChildContext(false)
                .build();

        assertEquals(strategy, config.retryStrategy());
        assertFalse(config.wrapInChildContext());
    }

    @Test
    void builderWithCustomLambdaRetryStrategy() {
        var config = RetryOperationConfig.builder()
                .retryStrategy((error, attempt) -> RetryDecision.fail())
                .build();

        assertNotNull(config.retryStrategy());
    }
}
