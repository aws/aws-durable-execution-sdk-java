// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

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
    }

    @Test
    void retryRejectsInvalidDelay() {
        assertThrows(NullPointerException.class, () -> ExtensionStepResult.retry("next", null));
        assertThrows(IllegalArgumentException.class, () -> ExtensionStepResult.retry("next", Duration.ofSeconds(-1)));
    }
}
