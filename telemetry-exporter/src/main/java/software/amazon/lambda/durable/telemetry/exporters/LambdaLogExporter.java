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
 * function's own log group, so this needs no extra IAM). Emits the {@code operationsByName} map as ONE line.
 *
 * <p>The line is the Lambda JSON log envelope {@code {"timestamp", "level": "INFO", "message"}} whose {@code message}
 * is the compact record JSON. The Java runtime does not wrap {@code System.out}, so this exporter writes the envelope
 * itself; Lambda does not encode a line that is already JSON a second time.
 *
 * <p><b>Line size.</b> {@link #maxRecordSizeBytes()} caps the printed line, envelope included. The record is embedded
 * in {@code message} as a JSON string, so every {@code "} and {@code \} in it is escaped again and the line can be up
 * to about twice the record. When the line is over the limit, {@link #export} truncates the record again, measuring the
 * escaped envelope, until the line fits.
 *
 * <p>Lines are written with {@code "level":"INFO"}. With the Lambda JSON log format and an application log level of
 * {@code WARN} or above, Lambda drops them.
 */
@Experimental
public final class LambdaLogExporter implements TelemetryExporter {
    /** UTC ISO-8601 with exactly three fraction digits. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Budget for the printed line, envelope included. CloudWatch Logs caps a single log event at 256 KB. */
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

    /** Builds the one-line Lambda JSON log envelope, keys in the order timestamp, level, message. */
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
