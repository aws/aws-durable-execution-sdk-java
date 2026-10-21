// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry.exporters;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryRecord;
import software.amazon.lambda.durable.telemetry.Json;
import software.amazon.lambda.durable.telemetry.TelemetryExporter;

/**
 * Exports workflow telemetry records to CloudWatch Logs via {@code System.out.println} (Lambda captures stdout to the
 * function's own log group, so this needs no extra IAM). Emits the {@code operationsByName} map as ONE single-line JSON
 * record, mirroring the JS {@code LambdaLogExporter} (which uses {@code console.log}).
 *
 * <p>The line is the Lambda JSON log envelope {@code {"timestamp", "level": "INFO", "message"}} whose {@code message}
 * is the compact record JSON. Readers of this destination filter on {@code level} and parse {@code message}. The Node
 * runtime adds that envelope around {@code console.log}, but Java {@code System.out} is not wrapped, so this exporter
 * writes it itself; Lambda does not re-wrap a line that is already JSON. {@link #maxRecordSizeBytes()} is measured on
 * the record ({@link #render}), not on the envelope.
 */
@Experimental
public final class LambdaLogExporter implements TelemetryExporter {
    /** ISO-8601 UTC with exactly three fraction digits, matching JS {@code Date.toISOString()}. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** CloudWatch Logs caps a single log event at 256 KB. */
    private final Integer maxRecordSizeBytes;

    public LambdaLogExporter() {
        this(256_000);
    }

    public LambdaLogExporter(Integer maxRecordSizeBytes) {
        this.maxRecordSizeBytes = maxRecordSizeBytes;
    }

    @Override
    public Integer maxRecordSizeBytes() {
        return maxRecordSizeBytes;
    }

    @Override
    public Object render(DurableExecutionTelemetryRecord record) {
        return record.toByNameWireMap();
    }

    @Override
    public void export(DurableExecutionTelemetryRecord record) {
        System.out.println(logLine(Json.stringify(render(record)), Instant.now()));
    }

    /** Builds the one-line Lambda JSON log envelope; keys in the shared cross-SDK order. */
    static String logLine(String recordJson, Instant now) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", TIMESTAMP.format(now));
        line.put("level", "INFO");
        line.put("message", recordJson);
        return Json.stringify(line);
    }
}
