// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Finding {@code arf_v1_cyyzil3ide53kqls24cwcz3cts} ([P2] snapshot execution input before handler/transform mutation):
 * the plugin must detach {@code executionInput} at {@code onInvocationStart} so a later handler mutation cannot corrupt
 * the cached snapshot, and must hand every content transform a separate defensive copy so a mutating transform cannot
 * corrupt the snapshot reused across multiple emissions.
 */
class InputSnapshotTest {

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

    private Map<String, OperationChangeItemInfo> ops() {
        Map<String, OperationChangeItemInfo> m = new LinkedHashMap<>();
        m.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        "greet",
                        "STEP",
                        "Step",
                        null,
                        START,
                        START.plusMillis(5),
                        OperationStatus.SUCCEEDED,
                        1,
                        false,
                        null,
                        null));
        return m;
    }

    @Test
    void handlerMutationAfterStartDoesNotCorruptCachedInputSnapshot() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(
                WorkflowInsightConfig.builder().addExporter(exporter).build());

        // A mutable input whose nested list the handler mutates after the invocation has started.
        List<Object> items = new ArrayList<>();
        items.add("a");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", items);

        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, input, ops(), Map.of()));
        // Simulate the user handler mutating the live input object mid-execution.
        items.add("b");
        input.put("added", "later");
        plugin.onInvocationEnd(
                new InvocationEndInfo("req", ARN, true, START, ops(), InvocationStatus.SUCCEEDED, null, input, "out"));

        assertEquals(1, exporter.records.size());
        var emitted = assertInstanceOf(Map.class, exporter.records.get(0).input);
        var emittedItems = assertInstanceOf(List.class, emitted.get("items"));
        assertEquals(1, emittedItems.size(), "cached snapshot reflects input at start, not the later handler mutation");
        assertEquals("a", emittedItems.get(0));
        assertEquals(false, emitted.containsKey("added"), "key added after start is not in the detached snapshot");
    }

    @Test
    void mutatingInputTransformDoesNotAccumulateAcrossEmissions() {
        // A transform that mutates its argument in place (appends a marker) and returns it. If the cached snapshot were
        // shared with the transform, markers would accumulate across the start/change/end emissions.
        Function<Object, Object> mutatingTransform = v -> {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) ((Map<String, Object>) v).get("items");
            list.add("MARK");
            return v;
        };
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .content(ContentConfig.builder()
                        .inputTransform(mutatingTransform)
                        .build())
                .addExporter(exporter)
                .build());

        List<Object> items = new ArrayList<>();
        items.add("a");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", items);

        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, input, ops(), Map.of()));
        plugin.onOperationChange(new OperationChangeInfo("req", ARN, ops(), ops()));
        plugin.onInvocationEnd(
                new InvocationEndInfo("req", ARN, true, START, ops(), InvocationStatus.SUCCEEDED, null, input, "out"));

        assertEquals(3, exporter.records.size());
        // Each emission's transform received a fresh copy: original "a" plus exactly one "MARK", never accumulating.
        for (WorkflowInsightRecord rec : exporter.records) {
            var emitted = assertInstanceOf(Map.class, rec.input);
            var emittedItems = assertInstanceOf(List.class, emitted.get("items"));
            assertEquals(2, emittedItems.size(), "transform sees a fresh snapshot copy each emission");
            assertEquals("a", emittedItems.get(0));
            assertEquals("MARK", emittedItems.get(1));
        }
    }
}
