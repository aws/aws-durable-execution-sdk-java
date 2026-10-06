// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Finding {@code arf_v1_hp5bcyxuzbh2vgqafztcdoghc5} ([P2] unwrap {@code UnrecoverableDurableExecutionException}):
 * execution-level errors can arrive wrapped as {@code UnrecoverableDurableExecutionException}, which also carries the
 * checkpointed {@link ErrorObject}. The record must derive the error name/message from that {@code ErrorObject} (the
 * original identity), through the same helper used for {@code DurableOperationException}, with null-field fallback.
 */
class UnrecoverableErrorUnwrapTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }
    }

    private static UnrecoverableDurableExecutionException unrecoverable(String errorType, String errorMessage) {
        ErrorObject error = ErrorObject.builder()
                .errorType(errorType)
                .errorMessage(errorMessage)
                .build();
        return new UnrecoverableDurableExecutionException(error);
    }

    private static Map<String, OperationChangeItemInfo> ops(OperationStatus status) {
        Map<String, OperationChangeItemInfo> m = new LinkedHashMap<>();
        m.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        "step",
                        "STEP",
                        "Step",
                        null,
                        START,
                        START.plusMillis(5),
                        status,
                        1,
                        false,
                        null,
                        null));
        return m;
    }

    @Test
    void failedExecutionUnwrapsUnrecoverableErrorObject() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(
                WorkflowInsightConfig.builder().addExporter(exporter).build());

        Throwable execError = unrecoverable("PoisonPayload", "cannot deserialize checkpoint");
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req", ARN, true, START, ops(OperationStatus.FAILED), InvocationStatus.FAILED, execError, "in", null));

        assertEquals(1, exporter.records.size());
        var rec = exporter.records.get(0);
        assertNotNull(rec.error);
        assertEquals("PoisonPayload", rec.error.name());
        assertEquals("cannot deserialize checkpoint", rec.error.message());
    }

    @Test
    void retryingExecutionUnwrapsUnrecoverableErrorObjectInOnChangeMode() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());

        Throwable execError = unrecoverable("TransientBackendError", "retry scheduled");
        // RETRYING maps to a non-terminal RUNNING status but still emits in ON_CHANGE mode.
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req",
                ARN,
                true,
                START,
                ops(OperationStatus.STARTED),
                InvocationStatus.RETRYING,
                execError,
                "in",
                null));

        assertEquals(1, exporter.records.size());
        var rec = exporter.records.get(0);
        assertEquals("RUNNING", rec.status());
        assertNotNull(rec.error);
        assertEquals("TransientBackendError", rec.error.name());
        assertEquals("retry scheduled", rec.error.message());
    }

    @Test
    void fallsBackToThrowableFieldsWhenUnrecoverableErrorTypeMissing() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(
                WorkflowInsightConfig.builder().addExporter(exporter).build());

        ErrorObject partial =
                ErrorObject.builder().errorMessage("only a message").build();
        Throwable execError = new UnrecoverableDurableExecutionException(partial);
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req", ARN, true, START, ops(OperationStatus.FAILED), InvocationStatus.FAILED, execError, "in", null));

        var rec = exporter.records.get(0);
        assertEquals(
                "UnrecoverableDurableExecutionException",
                rec.error.name(),
                "null errorType falls back to throwable class");
        assertEquals("only a message", rec.error.message());
    }
}
