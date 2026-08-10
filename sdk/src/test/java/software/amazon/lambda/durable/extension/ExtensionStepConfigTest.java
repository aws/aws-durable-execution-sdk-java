// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class ExtensionStepConfigTest {
    @Test
    void builderDefaultsToNullStateAndSerDes() {
        var config = ExtensionStepConfig.builder().build();

        assertNull(config.initialState());
        assertNull(config.serDes());
        assertNull(config.retryStrategy());
        assertEquals(ExtensionStepConfig.StepSemantics.AT_LEAST_ONCE_PER_RETRY, config.semanticsPerRetry());
    }

    @Test
    void builderRetainsConfiguredValues() {
        var serDes = new JacksonSerDes();
        ExtensionStepConfig.RetryStrategy<Integer> retryStrategy =
                (error, state, attempt) -> ExtensionStepResult.retry(state, Duration.ofSeconds(attempt));
        var config = ExtensionStepConfig.<Integer>builder()
                .initialState(42)
                .serDes(serDes)
                .retryStrategy(retryStrategy)
                .semanticsPerRetry(ExtensionStepConfig.StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .build();

        assertEquals(42, config.initialState());
        assertEquals(serDes, config.serDes());
        assertSame(retryStrategy, config.retryStrategy());
        assertEquals(ExtensionStepConfig.StepSemantics.AT_MOST_ONCE_PER_RETRY, config.semanticsPerRetry());
    }

    @Test
    void retryAndSemanticsContractsAreOwnedByExtensionStepConfig() throws Exception {
        assertEquals(
                ExtensionStepConfig.RetryStrategy.class,
                ExtensionStepConfig.class.getMethod("retryStrategy").getReturnType());
        assertEquals(
                ExtensionStepConfig.StepSemantics.class,
                ExtensionStepConfig.class.getMethod("semanticsPerRetry").getReturnType());
        assertEquals(
                ExtensionStepResult.RetryDecision.class,
                ExtensionStepConfig.RetryStrategy.class
                        .getMethod("makeRetryDecision", Throwable.class, Object.class, int.class)
                        .getReturnType());
    }
}
