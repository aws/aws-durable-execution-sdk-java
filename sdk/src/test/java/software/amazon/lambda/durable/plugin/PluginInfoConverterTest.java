// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.OperationSubType;

class PluginInfoConverterTest {

    private static final String OPERATION_ID = "op-1";
    private static final String RAW_ID = "1";
    private static final String OPERATION_NAME = "validate-order";
    private static final String PARENT_ID = "parent-ctx";
    private static final String RAW_PARENT_ID = "0";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T00:00:05Z");

    // ─── toOperationInfo (from Operation) ────────────────────────────────

    @Test
    void toOperationInfo_fromOperation_mapsAllFields() {
        var operation = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType("WaitForCondition")
                .parentId(PARENT_ID)
                .status(OperationStatus.SUCCEEDED)
                .startTimestamp(START)
                .endTimestamp(END)
                .build();

        var info = PluginInfoConverter.toOperationInfo(operation);

        assertEquals(OPERATION_ID, info.id());
        assertNull(info.rawId());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("STEP", info.type());
        assertEquals("WaitForCondition", info.subType());
        assertEquals(PARENT_ID, info.parentId());
        assertNull(info.rawParentId());
        assertEquals(START, info.startTimestamp());
        assertEquals(END, info.endTimestamp());
    }

    @Test
    void toOperationInfo_fromNullOperation_returnsAllNulls() {
        var info = PluginInfoConverter.toOperationInfo(null);

        assertNull(info.id());
        assertNull(info.rawId());
        assertNull(info.name());
        assertNull(info.type());
        assertNull(info.subType());
        assertNull(info.parentId());
        assertNull(info.rawParentId());
        assertNull(info.startTimestamp());
        assertNull(info.endTimestamp());
    }

    @Test
    void toOperationInfo_fromOperation_handlesNullFields() {
        var operation = Operation.builder()
                .id(OPERATION_ID)
                .status(OperationStatus.STARTED)
                .startTimestamp(START)
                .build();

        var info = PluginInfoConverter.toOperationInfo(operation);

        assertEquals(OPERATION_ID, info.id());
        assertNull(info.rawId());
        assertNull(info.name());
        assertNull(info.type());
        assertNull(info.subType());
        assertNull(info.parentId());
        assertNull(info.rawParentId());
        assertEquals(START, info.startTimestamp());
        assertNull(info.endTimestamp());
    }

    // ─── toOperationInfo (from explicit params) ──────────────────────────

    @Test
    void toOperationInfo_fromParams_mapsAllFields() {
        var operation =
                Operation.builder().startTimestamp(START).endTimestamp(END).build();

        var info = PluginInfoConverter.toOperationInfo(
                operation,
                OPERATION_ID,
                RAW_ID,
                OPERATION_NAME,
                OperationType.STEP,
                OperationSubType.WAIT_FOR_CONDITION,
                PARENT_ID,
                RAW_PARENT_ID);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(RAW_ID, info.rawId());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("STEP", info.type());
        assertEquals("WaitForCondition", info.subType());
        assertEquals(PARENT_ID, info.parentId());
        assertEquals(RAW_PARENT_ID, info.rawParentId());
        assertEquals(START, info.startTimestamp());
        assertEquals(END, info.endTimestamp());
    }

    @Test
    void toOperationInfo_fromParams_nullOperation_noTimestamps() {
        var info = PluginInfoConverter.toOperationInfo(
                null, OPERATION_ID, RAW_ID, OPERATION_NAME, OperationType.WAIT, OperationSubType.WAIT, null, null);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(RAW_ID, info.rawId());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("WAIT", info.type());
        assertEquals("Wait", info.subType());
        assertNull(info.parentId());
        assertNull(info.rawParentId());
        assertNull(info.startTimestamp());
        assertNull(info.endTimestamp());
    }

    @Test
    void toOperationInfo_fromParams_nullSubType() {
        var info = PluginInfoConverter.toOperationInfo(
                null, OPERATION_ID, RAW_ID, OPERATION_NAME, OperationType.STEP, null, null, null);

        assertNull(info.subType());
    }

    // ─── toOperationEndInfo ──────────────────────────────────────────────

    @Test
    void toOperationEndInfo_mapsAllFields() {
        var operation =
                Operation.builder().startTimestamp(START).endTimestamp(END).build();
        var error = new RuntimeException("step failed");

        var info = PluginInfoConverter.toOperationEndInfo(
                operation,
                OPERATION_ID,
                RAW_ID,
                OPERATION_NAME,
                OperationType.STEP,
                OperationSubType.STEP,
                PARENT_ID,
                RAW_PARENT_ID,
                error);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(RAW_ID, info.rawId());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("STEP", info.type());
        assertEquals("Step", info.subType());
        assertEquals(PARENT_ID, info.parentId());
        assertEquals(RAW_PARENT_ID, info.rawParentId());
        assertEquals(START, info.startTimestamp());
        assertEquals(END, info.endTimestamp());
        assertEquals(error, info.error());
    }

    @Test
    void toOperationEndInfo_nullError_forSuccess() {
        var operation =
                Operation.builder().startTimestamp(START).endTimestamp(END).build();

        var info = PluginInfoConverter.toOperationEndInfo(
                operation,
                OPERATION_ID,
                RAW_ID,
                OPERATION_NAME,
                OperationType.STEP,
                OperationSubType.STEP,
                null,
                null,
                null);

        assertNull(info.error());
    }

    // ─── toUserFunctionStartInfo ────────────────────────────────────────

    @Test
    void toUserFunctionStartInfo_stepAttempt() {
        var info = PluginInfoConverter.toUserFunctionStartInfo(
                OPERATION_ID,
                RAW_ID,
                OPERATION_NAME,
                OperationType.STEP,
                OperationSubType.STEP,
                PARENT_ID,
                RAW_PARENT_ID,
                false,
                3);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(RAW_ID, info.rawId());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("STEP", info.type());
        assertEquals("Step", info.subType());
        assertEquals(PARENT_ID, info.parentId());
        assertEquals(RAW_PARENT_ID, info.rawParentId());
        assertNotNull(info.startTimestamp());
        assertFalse(info.isReplay());
        assertEquals(3, info.attempt());
    }

    @Test
    void toUserFunctionStartInfo_contextOperation() {
        var info = PluginInfoConverter.toUserFunctionStartInfo(
                OPERATION_ID,
                RAW_ID,
                OPERATION_NAME,
                OperationType.CONTEXT,
                OperationSubType.MAP,
                PARENT_ID,
                RAW_PARENT_ID,
                true,
                null);

        assertEquals("CONTEXT", info.type());
        assertEquals("Map", info.subType());
        assertTrue(info.isReplay());
        assertNull(info.attempt());
    }

    // ─── toUserFunctionEndInfo ───────────────────────────────────────────

    @Test
    void toUserFunctionEndInfo_succeeded() {
        var startInfo = PluginInfoConverter.toUserFunctionStartInfo(
                OPERATION_ID,
                RAW_ID,
                OPERATION_NAME,
                OperationType.STEP,
                OperationSubType.STEP,
                PARENT_ID,
                RAW_PARENT_ID,
                false,
                1);

        var endInfo = PluginInfoConverter.toUserFunctionEndInfo(startInfo, true, null);

        assertEquals(OPERATION_ID, endInfo.id());
        assertEquals(RAW_ID, endInfo.rawId());
        assertEquals(OPERATION_NAME, endInfo.name());
        assertEquals(startInfo.startTimestamp(), endInfo.startTimestamp());
        assertNotNull(endInfo.endTimestamp());
        assertFalse(endInfo.isReplay());
        assertEquals(1, endInfo.attempt());
        assertTrue(endInfo.succeeded());
        assertNull(endInfo.error());
    }

    @Test
    void toUserFunctionEndInfo_failed() {
        var error = new RuntimeException("step failed");
        var startInfo = PluginInfoConverter.toUserFunctionStartInfo(
                OPERATION_ID, RAW_ID, OPERATION_NAME, OperationType.STEP, null, null, null, false, 2);

        var endInfo = PluginInfoConverter.toUserFunctionEndInfo(startInfo, false, error);

        assertFalse(endInfo.succeeded());
        assertEquals(error, endInfo.error());
        assertEquals(2, endInfo.attempt());
    }
}
