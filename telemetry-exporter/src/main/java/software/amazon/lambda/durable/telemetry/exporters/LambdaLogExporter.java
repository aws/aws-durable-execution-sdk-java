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
 * function's own log group, so this needs no extra IAM). Emits the {@code operationsByName} map as ONE line.
 *
 * <p>The line is the Lambda JSON log envelope {@code {"timestamp", "level": "INFO", "message"}} whose {@code message}
 * is the compact record JSON. The Java runtime does not wrap {@code System.out}, so this exporter writes the envelope
 * itself; Lambda does not encode a line that is already JSON a second time.
 *
 * <p><b>Line size.</b> {@link #render} returns the envelope, so the single truncation pass run before {@link #export}
 * measures the printed line, envelope and second escaping included (every {@code "} and {@code \} in the record is
 * escaped again inside {@code message}, so the line can be about twice the record). The pass drops error causes first,
 * then operation results, whole operations, input and output. Every error message is capped at 8,192 characters (at
 * most about 57 KB of line even when every character needs escaping twice), so once those are dropped the line fits any
 * limit of 64 KB or more, including the default 256,000 bytes. The execution {@code error} and the identity fields are
 * never dropped: with a limit smaller than those (for example a limit below the 68-byte envelope), the line is still
 * printed, over the limit, with everything droppable dropped and the markers set.
 *
 * <p>Lines carry an explicit {@code "level":"INFO"}. Lambda already treats a line without a level as INFO, so
 * application log level filtering is unchanged: with the JSON log format and an application log level of {@code WARN}
 * or above, these lines are dropped, as raw record lines were before.
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

    /**
     * Returns the envelope {@code {"timestamp", "level", "message"}} whose {@code message} is the compact
     * {@code operationsByName} record JSON. This is exactly what {@link #export} prints, so the size limit measures the
     * printed line. The timestamp always has the same width, so the size does not depend on when it is taken.
     */
    @Override
    public Object render(DurableExecutionTelemetryRecord record) {
        return envelope(Json.stringify(record.toByNameWireMap()), Instant.now());
    }

    @Override
    public void export(DurableExecutionTelemetryRecord record) {
        System.out.println(Json.stringify(render(record)));
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
