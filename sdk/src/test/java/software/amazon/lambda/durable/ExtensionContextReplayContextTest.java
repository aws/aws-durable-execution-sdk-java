// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;

class ExtensionContextReplayContextTest {
    @Test
    void lookupFailsOutsideExtensionContextFunction() {
        assertThrows(IllegalStateException.class, ExtensionContextReplayContext::getCurrentContext);
    }

    @Test
    void nestedScopesRestorePreviousReplayState() {
        try (var outer = ExtensionContextReplayContext.attach(false, "outer")) {
            var outerContext = ExtensionContextReplayContext.<String>getCurrentContext();
            assertFalse(outerContext.isReplayingChildren());
            assertEquals("outer", outerContext.getReplayState());

            try (var inner = ExtensionContextReplayContext.attach(true, "inner")) {
                var innerContext = ExtensionContextReplayContext.<String>getCurrentContext();
                assertTrue(innerContext.isReplayingChildren());
                assertEquals("inner", innerContext.getReplayState());
            }

            assertEquals(
                    "outer",
                    ExtensionContextReplayContext.<String>getCurrentContext().getReplayState());
        }
    }
}
