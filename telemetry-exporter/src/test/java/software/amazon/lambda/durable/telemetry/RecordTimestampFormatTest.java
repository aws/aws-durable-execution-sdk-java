// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;
import software.amazon.lambda.durable.telemetry.internal.Timestamps;

/**
 * Every record timestamp has one fixed-width UTC format, {@code YYYY-MM-DDTHH:MM:SS.sssZ}. Destinations compare these
 * values as text. Text order equals time order only when every value has the same width.
 */
class RecordTimestampFormatTest {

    private static final Pattern FORMAT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";

    /** Zero nanos: {@code Instant.toString()} writes no fraction for this value. */
    private static final Instant ZERO_NANOS = Instant.parse("2026-10-23T18:42:40Z");

    /** Nine fraction digits: {@code Instant.toString()} writes all nine. */
    private static final Instant NINE_DIGITS = Instant.parse("2026-10-23T18:42:44.078757096Z");

    /** The last nanosecond of a second: truncation must give {@code .999}, not round up to the next second. */
    private static final Instant LAST_NANO = Instant.parse("2026-10-23T18:42:46.999999999Z");

    @Test
    void formatWritesThreeFractionDigitsAndTruncates() {
        assertEquals("2026-10-23T18:42:40.000Z", Timestamps.format(ZERO_NANOS));
        assertEquals("2026-10-23T18:42:44.078Z", Timestamps.format(NINE_DIGITS));
        assertEquals("2026-10-23T18:42:46.999Z", Timestamps.format(LAST_NANO));
        assertEquals("2026-10-23T18:42:44.500Z", Timestamps.format(Instant.parse("2026-10-23T18:42:44.5Z")));
        assertEquals("1970-01-01T00:00:00.000Z", Timestamps.format(Instant.EPOCH));
    }

    @Test
    void fixedWidthValuesSortAsTextInTimeOrder() {
        // With Instant.toString(), "...40Z" sorts after "...40.500Z" as text, because 'Z' > '.'.
        String whole = Timestamps.format(ZERO_NANOS);
        String half = Timestamps.format(ZERO_NANOS.plusMillis(500));
        assertTrue(whole.compareTo(half) < 0, whole + " must sort before " + half);
    }

    @Test
    void everyRecordAndOperationTimestampHasTheFixedFormat() {
        var records = new CopyOnWriteArrayList<DurableExecutionTelemetryRecord>();
        var factory = DurableExecutionTelemetry.durableExecutionTelemetry(DurableExecutionTelemetryConfig.builder()
                .addExporter(records::add)
                .build());
        Map<String, OperationChangeItemInfo> ops = new LinkedHashMap<>();
        ops.put("op-1", op("op-1", "first", ZERO_NANOS, NINE_DIGITS));
        ops.put("op-2", op("op-2", "second", NINE_DIGITS, LAST_NANO));
        ops.put("op-3", op("op-3", "running", LAST_NANO, null));

        var plugin = Executions.started(factory, new InvocationInfo("req", ARN, true, ZERO_NANOS, "in", ops, Map.of()));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req", ARN, true, ZERO_NANOS, ops, InvocationStatus.SUCCEEDED, null, "in", "out"));

        assertEquals(1, records.size());
        var record = records.get(0);
        assertEquals("2026-10-23T18:42:40.000Z", record.startTime);
        assertFixed("emittedAt", record.emittedAt);
        assertFixed("endTime", record.endTime);

        List<OperationRecord> operations = record.operations();
        assertEquals(3, operations.size());
        assertEquals("2026-10-23T18:42:40.000Z", operations.get(0).startTime());
        assertEquals("2026-10-23T18:42:44.078Z", operations.get(0).endTime());
        assertEquals("2026-10-23T18:42:44.078Z", operations.get(1).startTime());
        assertEquals("2026-10-23T18:42:46.999Z", operations.get(1).endTime());
        assertEquals("2026-10-23T18:42:46.999Z", operations.get(2).startTime());
        assertNull(operations.get(2).endTime(), "a missing timestamp stays absent");
        for (var op : operations) {
            assertFixed(op.name() + ".startTime", op.startTime());
        }
    }

    private static OperationChangeItemInfo op(String id, String name, Instant start, Instant end) {
        return new OperationChangeItemInfo(
                id, name, "STEP", "Step", null, start, end, OperationStatus.SUCCEEDED, 1, false, null, null);
    }

    private static void assertFixed(String field, String value) {
        assertNotNull(value, field);
        assertTrue(FORMAT.matcher(value).matches(), field + " = " + value);
    }
}
