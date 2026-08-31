// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Finding {@code arf_v1_rzwdoyquezpr2qgby63imtmxcn} (exporter isolation) — mutable {@link Number} observation: the
 * deep-copy must not treat {@code Number} as an always-immutable shortcut, or mutable subclasses such as
 * {@link AtomicInteger}/{@link AtomicLong} (or a custom serializable {@code Number}) would be shared by reference and
 * let one exporter mutate a later exporter's record. Number values must be converted to detached immutable numeric
 * leaves while the emitted JSON stays numeric.
 */
class MutableNumberIsolationTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    /** A custom, mutable Number that serializes to a plain JSON number via {@link JsonValue}. */
    private static final class MutableNumber extends Number {
        private long value;

        MutableNumber(long value) {
            this.value = value;
        }

        void set(long v) {
            this.value = v;
        }

        @JsonValue
        long jsonValue() {
            return value;
        }

        @Override
        public int intValue() {
            return (int) value;
        }

        @Override
        public long longValue() {
            return value;
        }

        @Override
        public float floatValue() {
            return value;
        }

        @Override
        public double doubleValue() {
            return value;
        }
    }

    @Test
    void atomicIntegerIsDetachedAndStaysNumeric() {
        AtomicInteger n = new AtomicInteger(5);
        Object copy = Json.deepCopyContent(n);
        n.set(999); // mutate the original after copying

        assertNotSame(n, copy, "copy must not share the mutable AtomicInteger reference");
        assertEquals(5, assertInstanceOf(Number.class, copy).intValue(), "copy reflects pre-mutation value");
    }

    @Test
    void atomicLongIsDetachedAndStaysNumeric() {
        AtomicLong n = new AtomicLong(7L);
        Object copy = Json.deepCopyContent(n);
        n.set(-1L);

        assertNotSame(n, copy);
        assertEquals(7L, assertInstanceOf(Number.class, copy).longValue());
    }

    @Test
    void customSerializableNumberIsDetachedAndStaysNumeric() {
        MutableNumber n = new MutableNumber(42);
        Object copy = Json.deepCopyContent(n);
        n.set(0);

        assertNotSame(n, copy, "custom Number must not be shared by reference");
        assertEquals(42, assertInstanceOf(Number.class, copy).intValue());
        // Emitted JSON of the detached copy stays a plain number.
        assertEquals("42", Json.stringify(copy));
    }

    @Test
    void nestedMutableNumbersInMapAndListAreIsolatedButJsonStaysNumeric() {
        var mutating = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                // A hostile exporter cannot mutate an isolated immutable numeric leaf, but attempt any nested access.
                record.operations().forEach(op -> {});
            }
        };
        var good = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(mutating)
                .addExporter(good)
                .build());

        AtomicInteger topLevel = new AtomicInteger(3);
        List<Object> list = new ArrayList<>();
        list.add(new AtomicLong(11L));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("count", topLevel);
        input.put("list", list);
        input.put("custom", new MutableNumber(99));

        plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, input, ops(), Map.of()));
        // Mutate all originals after emission; isolated copies must be unaffected.
        topLevel.set(-1);
        ((AtomicLong) list.get(0)).set(-1L);

        assertEquals(1, good.records.size());
        var emitted = assertInstanceOf(Map.class, good.records.get(0).input);
        assertEquals(3, assertInstanceOf(Number.class, emitted.get("count")).intValue());
        var emittedList = assertInstanceOf(List.class, emitted.get("list"));
        assertEquals(11L, assertInstanceOf(Number.class, emittedList.get(0)).longValue());
        assertEquals(99, assertInstanceOf(Number.class, emitted.get("custom")).intValue());
        // JSON stays numeric (no object/bean rendering of the Number values).
        String json = Json.stringify(good.records.get(0).toWireMap());
        assertTrue(json.contains("\"count\":3"), json);
        assertTrue(json.contains("\"custom\":99"), json);
    }

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }
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
                        OperationStatus.SUCCEEDED,
                        1,
                        false,
                        null,
                        null));
        return m;
    }
}
