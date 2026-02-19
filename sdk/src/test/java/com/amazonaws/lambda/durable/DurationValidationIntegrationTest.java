// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationValidationIntegrationTest {

    @Test
    void callbackConfig_withInvalidTimeout_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> CallbackConfig.builder().timeout(Duration.ofMillis(500)).build());

        assertTrue(exception.getMessage().contains("Callback timeout"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void callbackConfig_withInvalidHeartbeatTimeout_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> CallbackConfig.builder()
                .heartbeatTimeout(Duration.ofMillis(999))
                .build());

        assertTrue(exception.getMessage().contains("Heartbeat timeout"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void callbackConfig_withValidTimeouts_shouldPass() {
        assertDoesNotThrow(() -> CallbackConfig.builder()
                .timeout(Duration.ofSeconds(30))
                .heartbeatTimeout(Duration.ofSeconds(10))
                .build());
    }

    @Test
    void callbackConfig_withNullTimeouts_shouldPass() {
        assertDoesNotThrow(() ->
                CallbackConfig.builder().timeout(null).heartbeatTimeout(null).build());
    }
}
