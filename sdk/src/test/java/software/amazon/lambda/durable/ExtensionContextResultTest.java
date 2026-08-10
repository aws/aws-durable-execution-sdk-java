// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.extension.ExtensionContextResult;

class ExtensionContextResultTest {
    @Test
    void completedStoresOnlyApplicationResult() {
        var result = ExtensionContextResult.completed("full");

        assertEquals("full", result.result());
        assertFalse(result.shouldReplayChildren(1024));
    }

    @Test
    void replayChildrenStoresReplayState() {
        var result = ExtensionContextResult.replayChildren("full", "state");

        assertEquals("full", result.result());
        assertEquals("state", result.replayState());
        assertTrue(result.shouldReplayChildren(1));
    }

    @Test
    void replayChildrenAboveSizeUsesSerializedFullResultSize() {
        var result = ExtensionContextResult.replayChildrenAboveSize("full", "state", 5);

        assertFalse(result.shouldReplayChildren(4));
        assertTrue(result.shouldReplayChildren(5));
    }

    @Test
    void replayChildrenAboveSizeRejectsInvalidThreshold() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ExtensionContextResult.replayChildrenAboveSize("full", "state", 0));
    }
}
