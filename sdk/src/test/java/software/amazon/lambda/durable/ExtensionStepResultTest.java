// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.extension.ExtensionStepResult;

class ExtensionStepResultTest {
    @Test
    void succeedCarriesFinalValue() {
        var result = ExtensionStepResult.succeed("done");

        assertEquals("done", result.value());
    }

    @Test
    void retryCarriesStateAndDelay() {
        var result = ExtensionStepResult.retry("next", Duration.ofSeconds(2));

        assertEquals("next", result.state());
        assertEquals(Duration.ofSeconds(2), result.delay());
        assertInstanceOf(ExtensionStepResult.RetryDecision.class, result);
        assertInstanceOf(ExtensionStepResult.RetryDecision.class, ExtensionStepResult.doNotRetry());
    }

    @Test
    void retryRejectsInvalidDelay() {
        assertThrows(NullPointerException.class, () -> ExtensionStepResult.retry("next", null));
        assertThrows(IllegalArgumentException.class, () -> ExtensionStepResult.retry("next", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ExtensionStepResult.retry("next", Duration.ofMillis(999)));
        assertThrows(IllegalArgumentException.class, () -> ExtensionStepResult.retry("next", Duration.ofSeconds(-1)));
    }

    @Test
    void retryAfterNormalizationEvaluatesDelayFromNormalizedState() {
        var result = ExtensionStepResult.retryAfterNormalization(
                "raw", state -> "normalized".equals(state) ? Duration.ofSeconds(3) : Duration.ofSeconds(1));

        assertEquals("raw", result.state());
        assertEquals(Duration.ofSeconds(3), result.delay("normalized"));
    }

    @Test
    void retryAfterNormalizationRejectsInvalidStrategyOrDelay() {
        assertThrows(NullPointerException.class, () -> ExtensionStepResult.retryAfterNormalization("next", null));
        assertThrows(
                NullPointerException.class, () -> ExtensionStepResult.retryAfterNormalization("next", state -> null)
                        .delay("normalized"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionStepResult.retryAfterNormalization(
                        "next", state -> Duration.ZERO)
                .delay("normalized"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionStepResult.retryAfterNormalization(
                        "next", state -> Duration.ofMillis(999))
                .delay("normalized"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionStepResult.retryAfterNormalization(
                        "next", state -> Duration.ofSeconds(-1))
                .delay("normalized"));
    }
}
