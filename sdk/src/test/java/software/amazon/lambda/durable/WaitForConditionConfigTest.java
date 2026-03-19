// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.WaitForConditionWaitStrategy;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class WaitForConditionConfigTest {

    @Test
    void builder_withDefaults_buildsConfigWithDefaultStrategy() {
        var config = WaitForConditionConfig.<String>builder().build();

        assertNotNull(config);
        assertNotNull(config.waitStrategy());
        assertNull(config.serDes());
    }

    @Test
    void builder_withCustomWaitStrategy_overridesDefault() {
        WaitForConditionWaitStrategy<String> customStrategy = WaitStrategies.fixedDelay(10, Duration.ofSeconds(2));
        var config = WaitForConditionConfig.<String>builder()
                .waitStrategy(customStrategy)
                .build();

        assertEquals(customStrategy, config.waitStrategy());
    }

    @Test
    void builder_withSerDes_setsSerDes() {
        var mockSerDes = new JacksonSerDes();
        var config = WaitForConditionConfig.<String>builder().serDes(mockSerDes).build();

        assertEquals(mockSerDes, config.serDes());
    }

    @Test
    void builder_withNullSerDes_allowsNull() {
        var config = WaitForConditionConfig.<String>builder().serDes(null).build();

        assertNull(config.serDes());
    }

    @Test
    void builder_defaultStrategy_producesValidDelay() {
        var config = WaitForConditionConfig.<String>builder().build();
        var strategy = config.waitStrategy();

        // The default strategy should produce a Duration for attempt 0
        var delay = strategy.evaluate("any-state", 0);
        assertNotNull(delay);
    }

    @Test
    void toBuilder_copiesValues() {
        WaitForConditionWaitStrategy<String> customStrategy = WaitStrategies.exponentialBackoff(
                10, Duration.ofSeconds(3), Duration.ofSeconds(60), 2.0, JitterStrategy.NONE);
        var mockSerDes = new JacksonSerDes();

        var original = WaitForConditionConfig.<String>builder()
                .waitStrategy(customStrategy)
                .serDes(mockSerDes)
                .build();

        var copied = original.toBuilder().build();

        assertEquals(customStrategy, copied.waitStrategy());
        assertEquals(mockSerDes, copied.serDes());
    }
}
