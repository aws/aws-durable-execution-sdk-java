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

    @Test
    void invokeConfig_withInvalidTimeout_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> InvokeConfig.builder().timeout(Duration.ofMillis(500)).build());

        assertTrue(exception.getMessage().contains("Invoke timeout"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void invokeConfig_withValidTimeout_shouldPass() {
        assertDoesNotThrow(
                () -> InvokeConfig.builder().timeout(Duration.ofSeconds(30)).build());
    }

    @Test
    void invokeConfig_withNullTimeout_shouldPass() {
        assertDoesNotThrow(() -> InvokeConfig.builder().timeout(null).build());
    }

    @Test
    void durableConfig_withInvalidPollingInterval_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> DurableConfig.builder()
                .withPollingInterval(Duration.ofMillis(500))
                .build());

        assertTrue(exception.getMessage().contains("Polling interval"));
        assertTrue(exception.getMessage().contains("at least 1 second"));
    }

    @Test
    void durableConfig_withValidPollingInterval_shouldPass() {
        assertDoesNotThrow(() -> DurableConfig.builder()
                .withPollingInterval(Duration.ofSeconds(2))
                .build());
    }

    @Test
    void durableConfig_withNullPollingInterval_shouldPass() {
        assertDoesNotThrow(
                () -> DurableConfig.builder().withPollingInterval(null).build());
    }
}
