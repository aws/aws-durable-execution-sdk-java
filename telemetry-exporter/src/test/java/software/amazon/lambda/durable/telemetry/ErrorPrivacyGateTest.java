// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Error gate. {@code ContentConfig.includeErrors(false)} suppresses every operation-level error — in the canonical
 * {@code operations} array rendering and the {@code operationsByName} rendering — and keeps the execution-level
 * {@code error}, matching the JS exporter.
 */
class ErrorPrivacyGateTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");
    private static final String EXEC_SECRET = "exec-secret-ssn-123-45-6789";
    private static final String OP_SECRET = "op-secret-token-abcdef";

    private static final class CapturingExporter implements TelemetryExporter {
        final List<DurableExecutionTelemetryRecord> records = new ArrayList<>();

        @Override
        public void export(DurableExecutionTelemetryRecord record) {
            records.add(record);
        }
    }

    private Map<String, OperationChangeItemInfo> failingOp() {
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
                        new RuntimeException(OP_SECRET),
                        null));
        return m;
    }

    private DurableExecutionTelemetryRecord runFailedExecution(boolean includeErrors, CapturingExporter exporter) {
        var plugin = Executions.plugin(
                DurableExecutionTelemetry.durableExecutionTelemetry(DurableExecutionTelemetryConfig.builder()
                        .content(ContentConfig.builder()
                                .includeErrors(includeErrors)
                                .build())
                        .addExporter(exporter)
                        .build()),
                ARN,
                START);
        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, "in", failingOp(), Map.of()));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req",
                ARN,
                true,
                START,
                failingOp(),
                InvocationStatus.FAILED,
                new RuntimeException(EXEC_SECRET),
                "in",
                null));
        assertEquals(1, exporter.records.size());
        return exporter.records.get(0);
    }

    @Test
    void includeErrorsFalseDropsOperationErrorsAndKeepsExecutionError() {
        var exporter = new CapturingExporter();
        var rec = runFailedExecution(false, exporter);

        assertEquals("FAILED", rec.status());
        assertNotNull(rec.error, "execution-level error kept with includeErrors:false (as in JS)");
        assertEquals("RuntimeException", rec.error.name());
        assertEquals(EXEC_SECRET, rec.error.message());
        assertNull(rec.operations().get(0).error(), "operation-level error suppressed by includeErrors:false");

        String arrayJson = Json.stringify(rec.toWireMap());
        String byNameJson = Json.stringify(rec.toByNameWireMap());
        assertTrue(arrayJson.contains(EXEC_SECRET), "execution error in the operations-array JSON");
        assertFalse(arrayJson.contains(OP_SECRET), "no operation error in the operations-array JSON");
        assertTrue(byNameJson.contains(EXEC_SECRET), "execution error in the operationsByName JSON");
        assertFalse(byNameJson.contains(OP_SECRET), "no operation error in the operationsByName JSON");
        @SuppressWarnings("unchecked")
        var byName = (Map<String, Object>) rec.toByNameWireMap().get("operationsByName");
        @SuppressWarnings("unchecked")
        var entry = (Map<String, Object>) byName.get("failing-step");
        assertFalse(entry.containsKey("error"), "no operationsByName.<n>.error with includeErrors:false");
    }

    @Test
    void includeErrorsTrueKeepsBothErrors() {
        var exporter = new CapturingExporter();
        var rec = runFailedExecution(true, exporter);

        assertNotNull(rec.error, "execution-level error present when includeErrors:true");
        assertEquals(EXEC_SECRET, rec.error.message());
        assertNotNull(rec.operations().get(0).error(), "operation-level error present when includeErrors:true");
        assertEquals(OP_SECRET, rec.operations().get(0).error().message());
    }
}
