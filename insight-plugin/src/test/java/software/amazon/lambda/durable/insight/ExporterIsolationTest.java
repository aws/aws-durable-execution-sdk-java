// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Finding {@code arf_v1_rzwdoyquezpr2qgby63imtmxcn} ([P2] prevent one exporter from mutating later exporters' records):
 * because truncation returns the original record when it already fits, every exporter would otherwise share one mutable
 * object. Each exporter must receive a deep copy so a hostile first exporter cannot corrupt records seen by exporters
 * that run after it.
 */
class ExporterIsolationTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static final class MutablePayload {
        public String value;

        MutablePayload(String value) {
            this.value = value;
        }
    }

    /** A malicious exporter that mutates operation fields and nested input content. */
    private static final class MutatingExporter implements InsightExporter {
        @Override
        @SuppressWarnings("unchecked")
        public void export(WorkflowInsightRecord record) {
            for (OperationRecord op : record.operations()) {
                op.name("HACKED").status("HACKED").result("HACKED");
            }
            if (record.input instanceof Map<?, ?> input) {
                ((Map<String, Object>) input).put("k", "HACKED");
                Object nested = input.get("pojo");
                if (nested instanceof Map<?, ?> nestedMap) {
                    ((Map<String, Object>) nestedMap).put("value", "HACKED");
                } else if (nested instanceof MutablePayload payload) {
                    payload.value = "HACKED";
                }
            }
        }
    }

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }
    }

    @Test
    void firstExporterMutationsDoNotLeakIntoLaterExporter() {
        var mutating = new MutatingExporter();
        var good = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .content(ContentConfig.builder()
                        .addOverride(OperationOverride.withResult("compute", r -> r))
                        .build())
                .addExporter(mutating)
                .addExporter(good)
                .build());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("k", "v");
        input.put("pojo", new MutablePayload("original"));
        Map<String, OperationChangeItemInfo> ops = new LinkedHashMap<>();
        ops.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        "compute",
                        "STEP",
                        "Step",
                        null,
                        START,
                        START.plusMillis(5),
                        OperationStatus.SUCCEEDED,
                        1,
                        false,
                        null,
                        "{\"x\":1}"));
        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, input, ops, Map.of()));

        assertEquals(1, good.records.size());
        var rec = good.records.get(0);
        OperationRecord op = rec.operations().get(0);
        assertEquals("compute", op.name(), "operation name not corrupted by the earlier exporter");
        assertEquals("SUCCEEDED", op.status(), "operation status not corrupted by the earlier exporter");
        assertInstanceOf(Map.class, op.result(), "operation result payload preserved");
        assertEquals(1, ((Map<?, ?>) op.result()).get("x"), "nested result content not corrupted");
        assertNotNull(rec.input);
        var copiedInput = assertInstanceOf(Map.class, rec.input);
        assertEquals("v", copiedInput.get("k"), "nested input content not corrupted by the earlier exporter");
        var copiedPojo = assertInstanceOf(Map.class, copiedInput.get("pojo"));
        assertEquals("original", copiedPojo.get("value"), "mutable POJO content not corrupted by the earlier exporter");
    }
}
