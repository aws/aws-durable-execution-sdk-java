// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class WaitForConditionConfigTest {

    private static final WaitForConditionWaitStrategy<String> DUMMY_STRATEGY =
            (state, attempt) -> WaitForConditionDecision.stopPolling();

    // ---- 2.4 Unit tests: builder validation ----

    @Test
    void builder_withValidArgs_buildsConfig() {
        var config = WaitForConditionConfig.builder(DUMMY_STRATEGY, "initial").build();

        assertNotNull(config);
        assertEquals(DUMMY_STRATEGY, config.waitStrategy());
        assertEquals("initial", config.initialState());
        assertNull(config.serDes());
    }

    @Test
    void builder_withNullWaitStrategy_throwsIllegalArgumentException() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> WaitForConditionConfig.builder(null, "initial"));

        assertTrue(exception.getMessage().contains("waitStrategy"));
    }

    @Test
    void builder_withNullInitialState_throwsIllegalArgumentException() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> WaitForConditionConfig.builder(DUMMY_STRATEGY, null));

        assertTrue(exception.getMessage().contains("initialState"));
    }

    @Test
    void builder_withBothNull_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> WaitForConditionConfig.builder(null, null));
    }

    @Test
    void builder_withSerDes_setsSerDes() {
        var mockSerDes = new software.amazon.lambda.durable.serde.JacksonSerDes();
        var config = WaitForConditionConfig.builder(DUMMY_STRATEGY, "initial")
                .serDes(mockSerDes)
                .build();

        assertEquals(mockSerDes, config.serDes());
    }

    @Test
    void builder_withNullSerDes_allowsNull() {
        var config = WaitForConditionConfig.builder(DUMMY_STRATEGY, "initial")
                .serDes(null)
                .build();

        assertNull(config.serDes());
    }

    // ---- 2.5 PBT: null waitStrategy or null initialState must always throw ----
    // **Validates: Requirements 3.2**

    @RepeatedTest(100)
    void pbt_nullWaitStrategy_alwaysThrows() {
        var random = new Random();
        var initialState = "state-" + random.nextInt(10000);

        assertThrows(IllegalArgumentException.class, () -> WaitForConditionConfig.builder(null, initialState));
    }

    @RepeatedTest(100)
    void pbt_nullInitialState_alwaysThrows() {
        var random = new Random();
        int variant = random.nextInt(3);
        WaitForConditionWaitStrategy<String> strategy =
                switch (variant) {
                    case 0 -> (state, attempt) -> WaitForConditionDecision.stopPolling();
                    case 1 ->
                        (state, attempt) ->
                                WaitForConditionDecision.continuePolling(Duration.ofSeconds(1 + random.nextInt(60)));
                    default ->
                        WaitStrategies.<String>builder(s -> true)
                                .maxAttempts(1 + random.nextInt(100))
                                .build();
                };

        assertThrows(IllegalArgumentException.class, () -> WaitForConditionConfig.builder(strategy, null));
    }
}
