// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;

class WithRetryConfigTest {

    @Test
    void builderWithRetryStrategy() {
        var strategy = RetryStrategies.Presets.DEFAULT;

        var config = WithRetryConfig.builder().retryStrategy(strategy).build();

        assertEquals(strategy, config.retryStrategy());
    }

    @Test
    void builderWithoutRetryStrategy_usesDefault() {
        var config = WithRetryConfig.builder().build();

        assertEquals(RetryStrategies.Presets.DEFAULT, config.retryStrategy());
    }

    @Test
    void builderChaining() {
        var strategy = RetryStrategies.Presets.DEFAULT;

        var config = WithRetryConfig.builder().retryStrategy(strategy).build();

        assertEquals(strategy, config.retryStrategy());
    }

    @Test
    void builderWithCustomLambdaRetryStrategy() {
        var config = WithRetryConfig.builder()
                .retryStrategy((error, attempt) -> RetryDecision.fail())
                .build();

        assertNotNull(config.retryStrategy());
    }

    @Test
    void wrapInChildContext_defaultsFalse() {
        var config = WithRetryConfig.builder().build();

        assertFalse(config.wrapInChildContext());
    }

    @Test
    void wrapInChildContext_canBeEnabled() {
        var config = WithRetryConfig.builder().wrapInChildContext(true).build();

        assertTrue(config.wrapInChildContext());
    }

    @Test
    void wrapInChildContext_canBeExplicitlyDisabled() {
        var config = WithRetryConfig.builder().wrapInChildContext(false).build();

        assertFalse(config.wrapInChildContext());
    }

    @Test
    void builderChaining_withWrapInChildContext() {
        var strategy = RetryStrategies.Presets.DEFAULT;

        var config = WithRetryConfig.builder()
                .retryStrategy(strategy)
                .wrapInChildContext(true)
                .build();

        assertEquals(strategy, config.retryStrategy());
        assertTrue(config.wrapInChildContext());
    }
}
