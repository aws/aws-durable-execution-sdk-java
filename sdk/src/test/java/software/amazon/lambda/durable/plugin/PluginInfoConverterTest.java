// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;

class PluginInfoConverterTest {

    private static final String OPERATION_ID = "op-1";
    private static final String OPERATION_NAME = "validate-order";
    private static final String PARENT_ID = "parent-ctx";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T00:00:05Z");

    private static final OperationIdentifier STEP_IDENTIFIER =
            OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.STEP);
    private static final OperationIdentifier WAIT_IDENTIFIER =
            OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.WAIT);
    private static final OperationIdentifier WAIT_FOR_CONDITION_IDENTIFIER =
            OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.WAIT_FOR_CONDITION);
    private static final OperationIdentifier MAP_IDENTIFIER =
            OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.MAP);

    // ─── toOperationInfo ─────────────────────────────────────────────────

    @Test
    void toOperationInfo_withIdentifier_mapsAllFields() {
        var operation =
                Operation.builder().startTimestamp(START).endTimestamp(END).build();

        var info = PluginInfoConverter.toOperationInfo(operation, WAIT_FOR_CONDITION_IDENTIFIER, PARENT_ID);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("STEP", info.type());
        assertEquals("WaitForCondition", info.subType());
        assertEquals(PARENT_ID, info.parentId());
        assertEquals(START, info.startTimestamp());
        assertEquals(END, info.endTimestamp());
    }

    @Test
    void toOperationInfo_withIdentifier_nullOperation_usesCurrentTime() {
        var before = Instant.now();
        var info = PluginInfoConverter.toOperationInfo(null, WAIT_IDENTIFIER, null);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("WAIT", info.type());
        assertEquals("Wait", info.subType());
        assertNull(info.parentId());
        assertNotNull(info.startTimestamp());
        assertFalse(info.startTimestamp().isBefore(before));
        assertNull(info.endTimestamp());
    }

    // ─── toOperationEndInfo ──────────────────────────────────────────────

    @Test
    void toOperationEndInfo_mapsAllFields() {
        var operation =
                Operation.builder().startTimestamp(START).endTimestamp(END).build();
        var error = new RuntimeException("step failed");

        var info = PluginInfoConverter.toOperationEndInfo(operation, STEP_IDENTIFIER, PARENT_ID, true, error);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("STEP", info.type());
        assertEquals("Step", info.subType());
        assertEquals(PARENT_ID, info.parentId());
        assertEquals(START, info.startTimestamp());
        assertEquals(END, info.endTimestamp());
        assertEquals(error, info.error());
    }

    @Test
    void toOperationEndInfo_nullError_forSuccess() {
        var operation =
                Operation.builder().startTimestamp(START).endTimestamp(END).build();

        var info = PluginInfoConverter.toOperationEndInfo(operation, STEP_IDENTIFIER, null, false, null);

        assertNull(info.error());
    }

    // ─── toUserFunctionStartInfo ────────────────────────────────────────

    @Test
    void toUserFunctionStartInfo_stepAttempt() {
        var info = PluginInfoConverter.toUserFunctionStartInfo(STEP_IDENTIFIER, PARENT_ID, false, 3);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("STEP", info.type());
        assertEquals("Step", info.subType());
        assertEquals(PARENT_ID, info.parentId());
        assertNotNull(info.startTimestamp());
        assertFalse(info.isReplayingChildren());
        assertEquals(3, info.attempt());
    }

    @Test
    void toUserFunctionStartInfo_contextOperation() {
        var info = PluginInfoConverter.toUserFunctionStartInfo(MAP_IDENTIFIER, PARENT_ID, true, null);

        assertEquals("CONTEXT", info.type());
        assertEquals("Map", info.subType());
        assertTrue(info.isReplayingChildren());
        assertNull(info.attempt());
    }

    // ─── toUserFunctionEndInfo ───────────────────────────────────────────

    @Test
    void toUserFunctionEndInfo_succeeded() {
        var startInfo = PluginInfoConverter.toUserFunctionStartInfo(STEP_IDENTIFIER, PARENT_ID, false, 1);

        var endInfo = PluginInfoConverter.toUserFunctionEndInfo(startInfo, true, null);

        assertEquals(OPERATION_ID, endInfo.id());
        assertEquals(OPERATION_NAME, endInfo.name());
        assertEquals(startInfo.startTimestamp(), endInfo.startTimestamp());
        assertNotNull(endInfo.endTimestamp());
        assertFalse(endInfo.isReplayingChildren());
        assertEquals(1, endInfo.attempt());
        assertTrue(endInfo.succeeded());
        assertNull(endInfo.error());
    }

    @Test
    void toUserFunctionEndInfo_failed() {
        var error = new RuntimeException("step failed");
        var startInfo = PluginInfoConverter.toUserFunctionStartInfo(STEP_IDENTIFIER, null, false, 2);

        var endInfo = PluginInfoConverter.toUserFunctionEndInfo(startInfo, false, error);

        assertFalse(endInfo.succeeded());
        assertEquals(error, endInfo.error());
        assertEquals(2, endInfo.attempt());
    }
}
