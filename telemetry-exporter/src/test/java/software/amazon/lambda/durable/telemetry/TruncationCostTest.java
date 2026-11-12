// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.telemetry.exporters.CloudWatchLogsExporter;
import software.amazon.lambda.durable.telemetry.exporters.OTelExporter;
import software.amazon.lambda.durable.telemetry.exporters.OperationsFormat;
import software.amazon.lambda.durable.telemetry.exporters.RenderCountingExporter;

/**
 * Size-limited built-in exporters other than {@code LambdaLogExporter} used to measure the whole record once per
 * operation. Issue #877: 4,000 simple steps at the CloudWatch limit took about 15 s.
 */
class TruncationCostTest {

    private static final int MANY_STEPS = 2_000;
    private static final int CLOUDWATCH_LIMIT = 256_000;
    /** A bounded pass checks a few dozen times. One check per operation is thousands. */
    private static final int FEW_MEASUREMENTS = 100;

    @Test
    void cloudWatchByNameShapeMeasuresAFewTimesAndKeepsTheExactOperations() {
        assertEquals(RenderCountingExporter.class.getPackageName(), CloudWatchLogsExporter.class.getPackageName());
        assertEquals(RenderCountingExporter.class.getPackageName(), OTelExporter.class.getPackageName());

        RenderCountingExporter exporter = new RenderCountingExporter(OperationsFormat.BY_NAME, CLOUDWATCH_LIMIT);
        DurableExecutionTelemetryRecord record = steps(MANY_STEPS, false);
        assertSameKeptOperations(record, exporter, FEW_MEASUREMENTS);
    }

    /**
     * {@code OperationsFormat.BOTH} and the OTel body (that JSON embedded as one string) must drop the same operations
     * as measuring every step. A bound that covers only one representation drops extra operations when each one carries
     * a large error.
     */
    @Test
    void bothFormatAndOtelBodyMatchTheExactPass() {
        DurableExecutionTelemetryRecord record = steps(30, true);
        assertSameKeptOperations(record, new RenderCountingExporter(OperationsFormat.BOTH, 80_000), FEW_MEASUREMENTS);

        OTelExporter otel = OTelExporter.builder()
                .endpoint("http://127.0.0.1:9/v1/logs")
                .operationsFormat(OperationsFormat.BOTH)
                .build();
        assertSameKeptOperations(record, new RenderCountingExporter(otel::render, 80_000), FEW_MEASUREMENTS);
    }

    /** A custom rendering stays on the exact pass: one check per step, same operations. */
    @Test
    void customRenderKeepsMeasuringEveryStep() {
        DurableExecutionTelemetryRecord record = steps(80, false);
        CustomExporter exporter = new CustomExporter(12_000);
        DurableExecutionTelemetry.exportRecord(record.deepCopy(), exporter);
        assertNotNull(exporter.exported, "export did not run");
        int measurements = exporter.renders.get();
        assertEquals(names(truncateExactly(record, exporter)), names(exporter.exported));
        assertTrue(
                measurements > record.operations().size(),
                measurements + " measurements; the exact pass checks every operation");
    }

    private static void assertSameKeptOperations(
            DurableExecutionTelemetryRecord record, RenderCountingExporter exporter, int maxMeasurements) {
        DurableExecutionTelemetry.exportRecord(record.deepCopy(), exporter);
        assertNotNull(exporter.exported, "export did not run");
        int measurements = exporter.renders.get();
        DurableExecutionTelemetryRecord exact = truncateExactly(record, exporter);
        assertEquals(exact.droppedOperations, exporter.exported.droppedOperations);
        assertEquals(names(exact), names(exporter.exported));
        assertNotNull(exporter.exported.droppedOperations, "fixture must be over the limit");
        assertTrue(
                measurements <= maxMeasurements,
                measurements + " measurements for " + record.operations().size() + " operations");
    }

    private static DurableExecutionTelemetryRecord truncateExactly(
            DurableExecutionTelemetryRecord record, TelemetryExporter exporter) {
        return Truncation.truncateRecord(record.deepCopy(), exporter.maxRecordSizeBytes(), exporter::render);
    }

    private static List<String> names(DurableExecutionTelemetryRecord record) {
        List<String> names = new ArrayList<>();
        for (OperationRecord op : record.operations()) {
            names.add(op.name());
        }
        return names;
    }

    /** {@code count} succeeded steps. When {@code withErrors} is set, each step has its own large error. */
    private static DurableExecutionTelemetryRecord steps(int count, boolean withErrors) {
        DurableExecutionTelemetryRecord record = new DurableExecutionTelemetryRecord();
        record.executionArn =
                "arn:aws:lambda:us-east-1:123456789012:function:demo:$LATEST/durable-execution/run-1/inv-1";
        record.emittedAt = "2026-10-28T00:00:00.000Z";
        record.executionName = "run-1";
        record.functionName = "demo";
        record.functionQualifier = "$LATEST";
        record.region = "us-east-1";
        record.accountId = "123456789012";
        record.durationMs = 1_000L;
        record.status = "SUCCEEDED";
        Instant start = Instant.parse("2026-10-28T00:00:00Z");
        for (int i = 0; i < count; i++) {
            Instant at = start.plusMillis(i * 10L);
            OperationRecord op = new OperationRecord()
                    .id("op-" + i)
                    .name("step-" + i)
                    .type("STEP")
                    .subType("Step")
                    .status("SUCCEEDED")
                    .startTime(at.toString())
                    .endTime(at.plusMillis(3).toString())
                    .durationMs(3L)
                    .attempt(1);
            if (withErrors) {
                op.status("FAILED").error(new ErrorInfo("E" + i, "e".repeat(3_000)));
            }
            record.addOperation(op);
        }
        return record;
    }

    /** Repeats the by-name map so one representation's shrink bound does not cover a drop. */
    private static final class CustomExporter implements TelemetryExporter {
        final AtomicInteger renders = new AtomicInteger();
        DurableExecutionTelemetryRecord exported;
        private final int maxBytes;

        CustomExporter(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public Integer maxRecordSizeBytes() {
            return maxBytes;
        }

        @Override
        public Object render(DurableExecutionTelemetryRecord record) {
            renders.incrementAndGet();
            List<Object> copies = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                copies.add(record.toByNameWireMap());
            }
            return copies;
        }

        @Override
        public void export(DurableExecutionTelemetryRecord record) {
            exported = record;
        }
    }
}
