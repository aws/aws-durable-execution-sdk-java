// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import software.amazon.lambda.durable.plugin.PluginRunner;

/**
 * Fix 2 — plugin {@link Throwable} containment. A plugin fault at any plugin-owned boundary (record construction, input
 * snapshotting, transforms, and each exporter's render/export/flush) must be caught — including {@link Error}s such as
 * an optional exporter's {@code NoClassDefFoundError} — so one failing exporter never blocks the others and no plugin
 * fault disrupts durable execution. Tests use deterministic {@link AssertionError}/{@link Error} throwers rather than
 * inducing a real {@code StackOverflowError}.
 */
class PluginThrowableContainmentTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();
        int flushes;

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            flushes++;
        }
    }

    /** A serializable POJO whose getter throws an {@link Error} during snapshot serialization. */
    public static final class ExplodingPayload {
        public String getValue() {
            throw new AssertionError("boom during input serialization");
        }
    }

    private Map<String, OperationChangeItemInfo> ops(String name) {
        Map<String, OperationChangeItemInfo> m = new LinkedHashMap<>();
        m.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        name,
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
        return m;
    }

    private InvocationInfo start(Object input) {
        return new InvocationInfo("req", ARN, true, START, input, ops("compute"), Map.of());
    }

    private InvocationEndInfo end(Object input) {
        return new InvocationEndInfo(
                "req", ARN, true, START, ops("compute"), InvocationStatus.SUCCEEDED, null, input, "out");
    }

    @Test
    void aNullExecutionArnEscapesNoHook() {
        // The SDK's contract is that a plugin fault never disrupts durable execution, so an invocation whose execution
        // ARN the plugin cannot use must be contained rather than thrown back. It is contained one step earlier now:
        // identity is taken when the instance is built, so the failure happens in the factory and no hook is ever
        // dispatched. That containment belongs to the SDK, so it is asserted through the SDK's own runner — which is
        // also what makes the old worst case ("the state removal runs last, in a finally, and a ConcurrentHashMap
        // cannot remove a null key") unreachable: there is no map and no removal.
        var exporter = new CapturingExporter();
        var environment = WorkflowInsight.workflowInsight(
                WorkflowInsightConfig.builder().addExporter(exporter).build());

        InvocationInfo nullStart = new InvocationInfo("req", null, true, START, "in", ops("compute"), Map.of());
        InvocationEndInfo nullEnd = new InvocationEndInfo(
                "req", null, true, START, ops("compute"), InvocationStatus.SUCCEEDED, null, "in", "out");

        var runner = new PluginRunner(List.of(environment));
        assertDoesNotThrow(() -> runner.onInvocationStart(nullStart), "onInvocationStart must contain a null ARN");
        assertDoesNotThrow(
                () -> runner.onOperationChange(new software.amazon.lambda.durable.plugin.OperationChangeInfo(
                        "req", null, ops("compute"), ops("compute"))),
                "onOperationChange must contain a null ARN");
        assertDoesNotThrow(() -> runner.onInvocationEnd(nullEnd), "onInvocationEnd must contain a null ARN");
        assertEquals(0, exporter.records.size(), "an invocation with no usable ARN emits nothing");

        // The environment is still usable afterwards: a well-formed invocation still emits and flushes.
        var plugin = Executions.plugin(environment, ARN, START);
        plugin.onInvocationStart(start("in"));
        plugin.onInvocationEnd(end("in"));
        assertEquals(1, exporter.records.size(), "the environment still works after a null-ARN invocation");
        assertTrue(exporter.flushes > 0);
    }

    @Test
    void exporterThrowingErrorIsIsolatedAndLaterExportersStillReceiveAndFlush() {
        var throwing = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                throw new AssertionError("exporter blew up with an Error");
            }
        };
        var good = new CapturingExporter();
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                        .addExporter(throwing)
                        .addExporter(good)
                        .build()),
                ARN,
                START);

        plugin.onInvocationStart(start("in"));
        plugin.onInvocationEnd(end("in"));

        assertEquals(1, good.records.size(), "a failing exporter (Error) never blocks the exporters after it");
        assertTrue(good.flushes > 0, "surviving exporter is still flushed");
    }

    @Test
    void inputSnapshotErrorOmitsInputButDoesNotDisruptExecution() {
        var exporter = new CapturingExporter();
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(
                        WorkflowInsightConfig.builder().addExporter(exporter).build()),
                ARN,
                START);

        // Snapshotting the input fails with an Error; the hook must not propagate it.
        plugin.onInvocationStart(start(new ExplodingPayload()));
        plugin.onInvocationEnd(end(null));

        assertEquals(1, exporter.records.size());
        assertNull(exporter.records.get(0).input, "input omitted when the snapshot throws");
        assertEquals(
                "SUCCEEDED", exporter.records.get(0).status(), "execution result unaffected by the snapshot fault");
    }

    @Test
    void throwingInputTransformOmitsInputWithoutFailure() {
        var exporter = new CapturingExporter();
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                        .content(ContentConfig.builder()
                                .inputTransform(v -> {
                                    throw new AssertionError("redactor blew up");
                                })
                                .build())
                        .addExporter(exporter)
                        .build()),
                ARN,
                START);

        plugin.onInvocationStart(start("in"));
        plugin.onInvocationEnd(end("in"));

        assertEquals(1, exporter.records.size());
        assertNull(exporter.records.get(0).input, "a throwing input transform omits the value");
        assertEquals("SUCCEEDED", exporter.records.get(0).status());
    }

    @Test
    void throwingResultTransformOmitsResultWithoutFailure() {
        var exporter = new CapturingExporter();
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                        .content(ContentConfig.builder()
                                .addOverride(OperationOverride.withResult("compute", r -> {
                                    throw new AssertionError("result redactor blew up");
                                }))
                                .build())
                        .addExporter(exporter)
                        .build()),
                ARN,
                START);

        plugin.onInvocationStart(start("in"));
        plugin.onInvocationEnd(end("in"));

        var rec = exporter.records.get(0);
        var op = rec.operations().stream()
                .filter(o -> "compute".equals(o.name()))
                .findFirst()
                .orElseThrow();
        assertNull(op.result(), "a throwing result transform omits the result");
        assertEquals("SUCCEEDED", rec.status());
    }

    @Test
    void oneFailingExporterInTheMiddleDoesNotBlockTheThird() {
        var first = new CapturingExporter();
        var throwing = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                throw new Error("hard error in the middle exporter");
            }
        };
        var third = new CapturingExporter();
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                        .addExporter(first)
                        .addExporter(throwing)
                        .addExporter(third)
                        .build()),
                ARN,
                START);

        plugin.onInvocationStart(start("in"));
        plugin.onInvocationEnd(end("in"));

        assertEquals(1, first.records.size(), "exporter before the fault still receives");
        assertEquals(1, third.records.size(), "exporter after the fault still receives");
    }
}
