// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Finding {@code arf_v1_qh6xoafzze3z3ccgrbppucmunr} ([P2] remove retained suspended execution state): per-execution
 * state must be removed on every {@code onInvocationEnd}, including non-terminal PENDING/RETRYING suspends, so a warm
 * container never leaks one entry per suspended execution. A resume re-seeds identical stable start time and input.
 */
class StateCleanupLifecycleTest {

    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }
    }

    private static String arn(int i) {
        return "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-" + i + "/invocation-1";
    }

    private static Map<String, OperationChangeItemInfo> ops() {
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
                        OperationStatus.STARTED,
                        1,
                        false,
                        null,
                        null));
        return m;
    }

    @Test
    void nDistinctPendingExecutionsLeaveNoRetainedState() {
        var plugin = (WorkflowInsight.InsightPlugin)
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder().build());

        int n = 25;
        for (int i = 0; i < n; i++) {
            String arn = arn(i);
            plugin.onInvocationStart(new InvocationInfo("req", arn, true, START, "in-" + i, ops(), Map.of()));
            // Each execution suspends (PENDING) and never terminates in this container.
            plugin.onInvocationEnd(new InvocationEndInfo(
                    "req", arn, true, START, ops(), InvocationStatus.PENDING, null, "in-" + i, null));
        }

        assertEquals(0, plugin.retainedStateCount(), "no per-execution state retained for suspended executions");
    }

    @Test
    void retryingSuspendAlsoLeavesNoRetainedState() {
        var plugin = (WorkflowInsight.InsightPlugin)
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder().build());
        String arn = arn(0);
        plugin.onInvocationStart(new InvocationInfo("req", arn, true, START, "in", ops(), Map.of()));
        plugin.onInvocationEnd(
                new InvocationEndInfo("req", arn, true, START, ops(), InvocationStatus.RETRYING, null, "in", null));
        assertEquals(0, plugin.retainedStateCount(), "RETRYING suspend also clears state");
    }

    @Test
    void resumeReSeedsStableStartTimeAndInput() {
        var exporter = new CapturingExporter();
        var plugin = (WorkflowInsight.InsightPlugin) WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());
        String arn = arn(0);

        // First invocation with input "alpha", then suspend (state removed).
        plugin.onInvocationStart(new InvocationInfo("req", arn, true, START, "alpha", ops(), Map.of()));
        plugin.onInvocationEnd(
                new InvocationEndInfo("req", arn, true, START, ops(), InvocationStatus.PENDING, null, "alpha", null));

        // Resume invocation: onInvocationStart re-seeds state from hook data (same START, same input).
        plugin.onInvocationStart(new InvocationInfo("req", arn, false, START, "alpha", ops(), Map.of()));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req", arn, true, START, ops(), InvocationStatus.SUCCEEDED, null, "alpha", "out"));

        var terminal = exporter.records.get(exporter.records.size() - 1);
        assertEquals("SUCCEEDED", terminal.status());
        assertEquals(START.toString(), terminal.startTime(), "stable start time recreated across the suspend boundary");
        assertEquals("alpha", terminal.input, "input re-seeded from resume onInvocationStart");
        assertEquals(0, plugin.retainedStateCount(), "terminal end also clears state");
    }
}
