// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;
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
        var operation = Operation.builder()
                .startTimestamp(START)
                .endTimestamp(END)
                .status(OperationStatus.STARTED)
                .build();

        var info = PluginInfoConverter.toOperationInfo(operation, WAIT_FOR_CONDITION_IDENTIFIER, PARENT_ID);

        assertEquals(OPERATION_ID, info.id());
        assertEquals(OPERATION_NAME, info.name());
        assertEquals("STEP", info.type());
        assertEquals("WaitForCondition", info.subType());
        assertEquals(PARENT_ID, info.parentId());
        assertEquals(START, info.startTimestamp());
        assertEquals(END, info.endTimestamp());
        assertEquals("STARTED", info.status());
    }

    @Test
    void toOperationInfo_withCustomIdentifier_preservesCustomSubtype() {
        var identifier = new PrimitiveOperationIdentifier(OPERATION_ID, OPERATION_NAME, OperationType.STEP, "AcmeStep");

        var info = PluginInfoConverter.toOperationInfo(null, identifier, PARENT_ID);

        assertEquals("STEP", info.type());
        assertEquals("AcmeStep", info.subType());
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
        assertNull(info.status());
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

    @Test
    void toOperationEndInfo_extractsResult_fromSucceededStep() {
        var operation = Operation.builder()
                .startTimestamp(START)
                .endTimestamp(END)
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"hello\"").build())
                .build();

        var info = PluginInfoConverter.toOperationEndInfo(operation, STEP_IDENTIFIER, null, false, null);

        assertEquals("\"hello\"", info.result());
    }

    @Test
    void toOperationEndInfo_extractsResult_fromSucceededChainedInvoke() {
        var operation = Operation.builder()
                .startTimestamp(START)
                .endTimestamp(END)
                .type(OperationType.CHAINED_INVOKE)
                .status(OperationStatus.SUCCEEDED)
                .chainedInvokeDetails(
                        ChainedInvokeDetails.builder().result("\"invoked\"").build())
                .build();

        var info = PluginInfoConverter.toOperationEndInfo(operation, STEP_IDENTIFIER, null, false, null);

        assertEquals("\"invoked\"", info.result());
    }

    @Test
    void toOperationEndInfo_extractsResult_fromSucceededCallback() {
        var operation = Operation.builder()
                .startTimestamp(START)
                .endTimestamp(END)
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(
                        CallbackDetails.builder().result("\"called-back\"").build())
                .build();

        var info = PluginInfoConverter.toOperationEndInfo(operation, STEP_IDENTIFIER, null, false, null);

        assertEquals("\"called-back\"", info.result());
    }

    @Test
    void toOperationEndInfo_extractsResult_fromSucceededContext() {
        var operation = Operation.builder()
                .startTimestamp(START)
                .endTimestamp(END)
                .type(OperationType.CONTEXT)
                .status(OperationStatus.SUCCEEDED)
                .contextDetails(
                        ContextDetails.builder().result("\"child-done\"").build())
                .build();

        var info = PluginInfoConverter.toOperationEndInfo(operation, STEP_IDENTIFIER, null, false, null);

        assertEquals("\"child-done\"", info.result());
    }

    @Test
    void toOperationEndInfo_resultIsNull_whenFailedWaitForConditionRetainsCheckpointState() {
        // A wait-for-condition is checkpointed as STEP and reuses stepDetails().result() to carry its
        // intermediate check-loop state between attempts, so a failed one can still hold state.
        var operation = Operation.builder()
                .startTimestamp(START)
                .endTimestamp(END)
                .type(OperationType.STEP)
                .status(OperationStatus.FAILED)
                .stepDetails(
                        StepDetails.builder().attempt(3).result("{\"polls\":2}").build())
                .build();

        var info = PluginInfoConverter.toOperationEndInfo(operation, STEP_IDENTIFIER, null, false, null);

        assertNull(info.result(), "a failed operation must not report intermediate state as its result");
    }

    @Test
    void operationEndInfo_compatibilityConstructor_leavesResultNull() {
        var info = new OperationEndInfo(
                OPERATION_ID, OPERATION_NAME, "STEP", "Step", PARENT_ID, START, END, "SUCCEEDED", 1, false, null);

        assertNull(info.result());
    }

    @Test
    void operationEndInfo_toString_omitsResult() {
        var info = new OperationEndInfo(
                OPERATION_ID,
                OPERATION_NAME,
                "STEP",
                "Step",
                PARENT_ID,
                START,
                END,
                "SUCCEEDED",
                1,
                false,
                null,
                "s3cret-result");

        var rendered = info.toString();

        assertFalse(rendered.contains("s3cret-result"), "operation result must not leak into logs");
        assertTrue(rendered.contains(OPERATION_ID));
        assertTrue(rendered.contains("SUCCEEDED"));
    }

    @Test
    void toOperationEndInfo_resultIsNull_whenOperationHasNoResult() {
        var operation = Operation.builder()
                .startTimestamp(START)
                .endTimestamp(END)
                .type(OperationType.WAIT)
                .status(OperationStatus.SUCCEEDED)
                .build();

        var info = PluginInfoConverter.toOperationEndInfo(operation, WAIT_IDENTIFIER, null, false, null);

        assertNull(info.result());
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
