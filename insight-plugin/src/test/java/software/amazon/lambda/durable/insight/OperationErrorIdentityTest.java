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
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Finding {@code arf_v1_lndcqjp4vnrm6vjmmfzrqrfdin} ([P2] preserve the checkpointed operation error type): operation
 * and execution snapshot errors arrive wrapped as {@code DurableOperationException}. The record must derive the error
 * name/message from the wrapper's {@code ErrorObject} (the original checkpointed identity), falling back to the
 * throwable's own fields only for values the {@code ErrorObject} leaves null.
 */
class OperationErrorIdentityTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static DurableOperationException wrapped(String errorType, String errorMessage) {
        ErrorObject error = ErrorObject.builder()
                .errorType(errorType)
                .errorMessage(errorMessage)
                .build();
        return new DurableOperationException(Operation.builder().id("op-1").build(), error);
    }

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }
    }

    private Map<String, OperationChangeItemInfo> failedOps(Throwable opError) {
        Map<String, OperationChangeItemInfo> m = new LinkedHashMap<>();
        m.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        "failing-step",
                        "STEP",
                        "Step",
                        null,
                        START,
                        START.plusMillis(5),
                        OperationStatus.FAILED,
                        1,
                        false,
                        opError,
                        null));
        return m;
    }

    @Test
    void operationAndExecutionErrorUseCheckpointedErrorObjectIdentity() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(
                WorkflowInsightConfig.builder().addExporter(exporter).build());

        Throwable opError = wrapped("CustomerValidationError", "invalid postal code");
        Throwable execError = wrapped("OrchestrationFailure", "workflow aborted");
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req", ARN, true, START, failedOps(opError), InvocationStatus.FAILED, execError, "in", null));

        assertEquals(1, exporter.records.size());
        var rec = exporter.records.get(0);

        // Execution-level error identity.
        assertNotNull(rec.error);
        assertEquals("OrchestrationFailure", rec.error.name());
        assertEquals("workflow aborted", rec.error.message());

        // Operation-level error identity — exact original type and message, not the DurableOperationException wrapper.
        OperationRecord op = rec.operations().get(0);
        assertNotNull(op.error());
        assertEquals("CustomerValidationError", op.error().name());
        assertEquals("invalid postal code", op.error().message());
    }

    @Test
    void fallsBackToThrowableFieldsWhenErrorObjectFieldsMissing() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(
                WorkflowInsightConfig.builder().addExporter(exporter).build());

        // ErrorObject present but errorType null: name falls back to the throwable's simple class name.
        ErrorObject partial =
                ErrorObject.builder().errorMessage("only a message").build();
        Throwable opError =
                new DurableOperationException(Operation.builder().id("op-1").build(), partial);
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req", ARN, true, START, failedOps(opError), InvocationStatus.FAILED, opError, "in", null));

        var rec = exporter.records.get(0);
        assertEquals("DurableOperationException", rec.error.name(), "null errorType falls back to throwable class");
        assertEquals("only a message", rec.error.message());
    }
}
