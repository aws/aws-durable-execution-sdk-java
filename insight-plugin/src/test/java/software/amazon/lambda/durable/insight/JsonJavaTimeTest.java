// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Finding {@code arf_v1_blkdgaotf7rzua2ga3uyouqm5e} ([P1] support the SDK's default payload types when exporting): the
 * mapper must serialize Java-time values (as {@code JacksonSerDes} does) so an included {@code Instant} in an
 * input/output/result serializes to ISO-8601 instead of throwing and silently dropping the record.
 */
class JsonJavaTimeTest {

    private static final Instant TS = Instant.parse("2026-08-05T12:34:56Z");
    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    @Test
    void instantSerializesAsIso8601String() {
        assertEquals("\"2026-08-05T12:34:56Z\"", Json.stringify(TS));
    }

    @Test
    void byteSizeOfPayloadContainingInstantIsNotNull() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("when", TS);
        payload.put("label", "created");
        Integer size = Json.byteSize(payload);
        assertNotNull(size, "a payload containing an Instant must be measurable, not silently unserializable");
        assertTrue(size > 0);
    }

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }
    }

    @Test
    void pluginOutputWithInstantInInputSerializesInsteadOfDropping() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("startedAt", TS);
        Map<String, OperationChangeItemInfo> ops = new LinkedHashMap<>();
        ops.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        "greet",
                        "STEP",
                        "Step",
                        null,
                        START,
                        START.plusMillis(5),
                        OperationStatus.STARTED,
                        1,
                        false,
                        null,
                        null));
        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, input, ops, Map.of()));

        assertEquals(1, exporter.records.size());
        var rec = exporter.records.get(0);
        // The emitted wire JSON must serialize (no exception, non-null size) and carry the ISO-8601 Instant.
        assertNotNull(Json.byteSize(rec.toWireMap()), "record with an Instant input must serialize");
        assertTrue(Json.stringify(rec.toWireMap()).contains("2026-08-05T12:34:56Z"));
    }
}
