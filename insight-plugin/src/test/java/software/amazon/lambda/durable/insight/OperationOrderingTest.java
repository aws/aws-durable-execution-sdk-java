// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * Finding {@code arf_v1_ig2yofnonmfn7zknekoxwd6xmg} ([P2] establish a deterministic chronological operation order): the
 * hook contract supplies a map with no iteration-order guarantee. Operations must be sorted by {@code startTimestamp}
 * (null last) with a stable id tie-breaker before records are built, and the {@code operationsByName} "latest" scalar
 * fields must reflect that chronological order.
 */
class OperationOrderingTest {

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

    private static OperationChangeItemInfo item(
            String id, String name, String type, String subType, Instant start, OperationStatus status) {
        Instant end = start == null ? null : start.plusMillis(1);
        return new OperationChangeItemInfo(id, name, type, subType, null, start, end, status, 1, false, null, null);
    }

    private WorkflowInsightRecord emitStart(Map<String, OperationChangeItemInfo> ops) {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());
        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, "in", ops, Map.of()));
        return exporter.records.get(0);
    }

    @Test
    void shuffledStartTimestampsAreEmittedChronologically() {
        // Inserted deliberately out of order: c (T+30), a (T+10), b (T+20).
        Map<String, OperationChangeItemInfo> ops = new LinkedHashMap<>();
        ops.put("c", item("c", "c-op", "STEP", "Step", START.plusMillis(30), OperationStatus.SUCCEEDED));
        ops.put("a", item("a", "a-op", "STEP", "Step", START.plusMillis(10), OperationStatus.SUCCEEDED));
        ops.put("b", item("b", "b-op", "STEP", "Step", START.plusMillis(20), OperationStatus.SUCCEEDED));

        var rec = emitStart(ops);
        List<String> ids = rec.operations().stream().map(OperationRecord::id).toList();
        assertEquals(List.of("a", "b", "c"), ids, "operations sorted by startTimestamp ascending");
    }

    @Test
    void equalTimestampsBreakTiesByStableId() {
        Instant t = START.plusMillis(10);
        Map<String, OperationChangeItemInfo> ops = new LinkedHashMap<>();
        ops.put("z", item("z", "z-op", "STEP", "Step", t, OperationStatus.SUCCEEDED));
        ops.put("m", item("m", "m-op", "STEP", "Step", t, OperationStatus.SUCCEEDED));
        ops.put("a", item("a", "a-op", "STEP", "Step", t, OperationStatus.SUCCEEDED));

        var rec = emitStart(ops);
        List<String> ids = rec.operations().stream().map(OperationRecord::id).toList();
        assertEquals(List.of("a", "m", "z"), ids, "equal timestamps break ties by ascending id");
    }

    @Test
    void nullTimestampsSortLast() {
        Map<String, OperationChangeItemInfo> ops = new LinkedHashMap<>();
        ops.put("n", item("n", "n-op", "STEP", "Step", null, OperationStatus.STARTED));
        ops.put("b", item("b", "b-op", "STEP", "Step", START.plusMillis(20), OperationStatus.SUCCEEDED));
        ops.put("a", item("a", "a-op", "STEP", "Step", START.plusMillis(10), OperationStatus.SUCCEEDED));

        var rec = emitStart(ops);
        List<String> ids = rec.operations().stream().map(OperationRecord::id).toList();
        assertEquals(List.of("a", "b", "n"), ids, "null startTimestamp sorts last");
    }

    @Test
    void repeatedNameLatestScalarsReflectChronologicalOrderNotMapOrder() {
        // Same name "task": later occurrence inserted FIRST so a naive values() walk would report the older one.
        Map<String, OperationChangeItemInfo> ops = new LinkedHashMap<>();
        ops.put("late", item("late", "task", "STEP", "StepV2", START.plusMillis(20), OperationStatus.SUCCEEDED));
        ops.put("early", item("early", "task", "STEP", "StepV1", START.plusMillis(10), OperationStatus.FAILED));

        var rec = emitStart(ops);
        Map<String, OperationSummary> byName = OperationsIndex.buildOperationsByName(rec.operations());
        OperationSummary task = byName.get("task");
        assertEquals(2, task.count);
        // Chronologically last occurrence is the T+20 SUCCEEDED/StepV2 one.
        assertEquals("SUCCEEDED", task.status, "latest status reflects the chronologically last occurrence");
        assertEquals("StepV2", task.subType, "latest subType reflects the chronologically last occurrence");
        assertEquals(1, task.failedCount, "the earlier FAILED occurrence is still counted");
        assertNull(task.result, "repeated name drops the representative result");
    }
}
