// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry.exporters;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryRecord;
import software.amazon.lambda.durable.telemetry.Json;
import software.amazon.lambda.durable.telemetry.TelemetryExporter;
import software.amazon.lambda.durable.telemetry.Truncation;

/**
 * Exports workflow telemetry records to CloudWatch Logs via {@code System.out.println} (Lambda captures stdout to the
 * function's own log group, so this needs no extra IAM). Emits the {@code operationsByName} map as ONE single-line JSON
 * record, mirroring the JS {@code LambdaLogExporter} (which uses {@code console.log}).
 *
 * <p>The line is the Lambda JSON log envelope {@code {"timestamp", "level": "INFO", "message"}} whose {@code message}
 * is the compact record JSON. Readers of this destination filter on {@code level} and parse {@code message}. The Node
 * runtime adds that envelope around {@code console.log}, but Java {@code System.out} is not wrapped, so this exporter
 * writes it itself; Lambda does not re-wrap a line that is already JSON.
 *
 * <p><b>Line size.</b> {@link #maxRecordSizeBytes()} caps the printed line, envelope included. The record is embedded
 * in {@code message} as a JSON string, so every {@code "} and {@code \} in it is escaped again and the line can be up
 * to about twice the record. When the line is over the limit, {@link #export} truncates the record again, measuring the
 * escaped envelope, until the line fits. With the default 256,000-byte limit every line stays well within the
 * CloudWatch Logs limit of 1 MB per log event, and so does any limit up to 1 MB.
 *
 * <p>Lines are written with {@code "level":"INFO"}. With the Lambda JSON log format and an application log level of
 * {@code WARN} or above, Lambda drops them.
 */
@Experimental
public final class LambdaLogExporter implements TelemetryExporter {
    /** ISO-8601 UTC with exactly three fraction digits, matching JS {@code Date.toISOString()}. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Budget for the printed line, envelope included (see the class documentation). */
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
        System.out.println(fittedLine(record, maxRecordSizeBytes, Instant.now()));
    }

    /**
     * Returns the envelope line for {@code record}, truncated so that the line, escaping included, is at most
     * {@code maxBytes} bytes. The record passed in was truncated against {@link #render}, which does not count the
     * second escaping of {@code message}, so it is truncated again here with the envelope as the measured rendering.
     */
    String fittedLine(DurableExecutionTelemetryRecord record, Integer maxBytes, Instant now) {
        String line = logLine(Json.stringify(render(record)), now);
        if (maxBytes == null || maxBytes <= 0 || line.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return line;
        }
        var fitted = Truncation.truncateRecord(record, maxBytes, r -> envelope(Json.stringify(render(r)), now));
        return logLine(Json.stringify(render(fitted)), now);
    }

    /** Builds the one-line Lambda JSON log envelope; keys in the shared cross-SDK order. */
    static String logLine(String recordJson, Instant now) {
        return Json.stringify(envelope(recordJson, now));
    }

    private static Map<String, Object> envelope(String recordJson, Instant now) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", TIMESTAMP.format(now));
        line.put("level", "INFO");
        line.put("message", recordJson);
        return line;
    }
}
