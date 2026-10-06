// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * Fix 3 — transform contract. Documents and pins the maintainer's minimum-unblock behavior: content transforms receive
 * a detached, JSON-compatible value (POJO&nbsp;-&gt;&nbsp;Map, {@code Instant}&nbsp;-&gt;&nbsp;ISO-8601 String), a
 * fresh copy per invocation so mutation cannot corrupt the cached snapshot, and a throwing transform omits the field
 * without failing the execution. No generic type-preserving cloning is attempted.
 */
class TransformContractTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }
    }

    /** A plain POJO with public fields so Jackson renders it to a JSON object. */
    public static final class Point {
        public int x;
        public int y;

        public Point() {}

        Point(int x, int y) {
            this.x = x;
            this.y = y;
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

    private WorkflowInsightRecord runOnce(Object input, Function<Object, Object> inputTransform) {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .content(ContentConfig.builder().inputTransform(inputTransform).build())
                .addExporter(exporter)
                .build());
        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, input, ops(), Map.of()));
        plugin.onInvocationEnd(
                new InvocationEndInfo("req", ARN, true, START, ops(), InvocationStatus.SUCCEEDED, null, input, "out"));
        return exporter.records.get(0);
    }

    @Test
    void inputTransformReceivesMapForPojo() {
        Object[] captured = new Object[1];
        runOnce(new Point(3, 4), v -> {
            captured[0] = v;
            return v;
        });
        var map = assertInstanceOf(Map.class, captured[0], "POJO input is presented to the transform as a Map");
        assertEquals(3, map.get("x"));
        assertEquals(4, map.get("y"));
    }

    @Test
    void inputTransformReceivesStringForInstant() {
        Object[] captured = new Object[1];
        var rec = runOnce(Instant.parse("2026-08-05T12:34:56Z"), v -> {
            captured[0] = v;
            return v;
        });
        assertInstanceOf(String.class, captured[0], "Instant is presented to the transform as its JSON string");
        assertEquals("2026-08-05T12:34:56Z", captured[0]);
        assertEquals("2026-08-05T12:34:56Z", rec.input, "emitted input carries the JSON representation");
    }

    @Test
    void eachTransformInvocationReceivesAFreshDetachedCopy() {
        var exporter = new CapturingExporter();
        List<Integer> seenSizes = Collections.synchronizedList(new ArrayList<>());
        Function<Object, Object> mutating = v -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            seenSizes.add(m.size());
            m.put("injected-" + m.size(), Boolean.TRUE); // mutate the argument in place
            return m;
        };
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .content(ContentConfig.builder().inputTransform(mutating).build())
                .addExporter(exporter)
                .build());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("a", 1);
        input.put("b", 2);
        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, input, ops(), Map.of()));
        plugin.onOperationChange(new OperationChangeInfo("req", ARN, ops(), ops()));
        plugin.onInvocationEnd(
                new InvocationEndInfo("req", ARN, true, START, ops(), InvocationStatus.SUCCEEDED, null, input, "out"));

        assertEquals(3, seenSizes.size(), "start + change + end each ran the transform");
        assertEquals(
                List.of(2, 2, 2),
                seenSizes,
                "each invocation sees a pristine 2-key copy: a mutating transform never corrupts the cached snapshot");
    }

    @Test
    void throwingTransformOmitsInputWithoutFailingExecution() {
        var exporter = new CapturingExporter();
        var plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .content(ContentConfig.builder()
                        .inputTransform(v -> {
                            throw new AssertionError("redactor blew up");
                        })
                        .build())
                .addExporter(exporter)
                .build());
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step("greet", String.class, sc -> "hi"),
                DurableConfig.builder().withPlugins(plugin).build());

        var result = runner.runUntilComplete("World");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus(), "a throwing transform does not fail the execution");
        assertEquals(1, exporter.records.size());
        assertNull(exporter.records.get(0).input, "the failing transform's value is omitted");
    }
}
