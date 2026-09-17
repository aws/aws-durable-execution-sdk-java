// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Finding {@code arf_v1_qh6xoafzze3z3ccgrbppucmunr} ([P2] remove retained suspended execution state): a warm container
 * must never accumulate per-execution state, including for executions that suspend (PENDING/RETRYING) and never
 * terminate in that container. A resume re-seeds identical stable start time and input.
 *
 * <p>The plugin used to keep that state in an ARN-keyed map and remove the entry at every invocation end, so the test
 * counted the entries left behind. There is no map now — an invocation's state <em>is</em> its plugin instance, which
 * the SDK creates per invocation and drops when it returns — so the two things worth proving are that the environment
 * (the factory's scheduler, which does outlive invocations) owes a finished invocation nothing, and that it holds no
 * reference to the instance once the invocation is over. A retained entry of any kind would fail the second assertion,
 * which the old count could not make: it could only count the entries the plugin knew it had.
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

    private static InvocationInfo start(int i) {
        return new InvocationInfo("req", arn(i), true, START, "in-" + i, ops(), Map.of());
    }

    private static InvocationEndInfo end(int i, InvocationStatus status) {
        return new InvocationEndInfo("req", arn(i), true, START, ops(), status, null, "in-" + i, null);
    }

    /**
     * Runs one whole invocation in the given environment and returns a weak reference to the instance that served it,
     * keeping no strong reference of its own — so whatever the reference still points at afterwards is retained by the
     * environment, not by this test.
     */
    private static WeakReference<InsightPlugin> runInvocation(
            DurableExecutionPluginFactory environment, int i, InvocationStatus status) {
        InsightPlugin plugin = Executions.started(environment, start(i));
        plugin.onInvocationEnd(end(i, status));
        assertFalse(Executions.outstanding(plugin), "the scheduler still owes execution " + i + " work");
        return new WeakReference<>(plugin);
    }

    /** True once every instance has been collected; polls, because a single GC request need not clear them. */
    private static boolean allCollected(List<WeakReference<InsightPlugin>> instances) {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (instances.stream().allMatch(reference -> reference.get() == null)) {
                return true;
            }
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return instances.stream().allMatch(reference -> reference.get() == null);
    }

    @Test
    void nDistinctPendingExecutionsLeaveNoRetainedState() {
        var environment =
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder().build());

        int n = 25;
        var instances = new ArrayList<WeakReference<InsightPlugin>>();
        for (int i = 0; i < n; i++) {
            // Each execution suspends (PENDING) and never terminates in this container.
            instances.add(runInvocation(environment, i, InvocationStatus.PENDING));
        }

        assertTrue(
                allCollected(instances),
                "the environment still holds the state of a suspended execution after its invocation ended");
    }

    @Test
    void retryingSuspendAlsoLeavesNoRetainedState() {
        var environment =
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder().build());
        var instance = runInvocation(environment, 0, InvocationStatus.RETRYING);
        assertTrue(allCollected(List.of(instance)), "a RETRYING suspend leaves nothing retained either");
    }

    @Test
    void resumeReSeedsStableStartTimeAndInput() {
        var exporter = new CapturingExporter();
        var environment = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());

        // First invocation with input "alpha", then suspend. Its instance is dropped with it.
        var first = Executions.started(
                environment, new InvocationInfo("req", arn(0), true, START, "alpha", ops(), Map.of()));
        first.onInvocationEnd(new InvocationEndInfo(
                "req", arn(0), true, START, ops(), InvocationStatus.PENDING, null, "alpha", null));

        // Resume invocation: a new instance, seeded from the resume's own hook data (same START, same input).
        var resumed = Executions.started(
                environment, new InvocationInfo("req", arn(0), false, START, "alpha", ops(), Map.of()));
        resumed.onInvocationEnd(new InvocationEndInfo(
                "req", arn(0), true, START, ops(), InvocationStatus.SUCCEEDED, null, "alpha", "out"));

        var terminal = exporter.records.get(exporter.records.size() - 1);
        assertEquals("SUCCEEDED", terminal.status());
        assertEquals(START.toString(), terminal.startTime(), "stable start time recreated across the suspend boundary");
        assertEquals("alpha", terminal.input, "input re-seeded from resume onInvocationStart");
        assertFalse(Executions.outstanding(resumed), "the terminal end leaves the scheduler owing nothing");
    }
}
