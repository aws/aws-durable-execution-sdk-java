// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry.exporters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryRecord;
import software.amazon.lambda.durable.telemetry.Json;
import software.amazon.lambda.durable.telemetry.RecordFactory;
import software.amazon.lambda.durable.telemetry.Truncation;

/**
 * The {@code LambdaLogExporter} line is the Lambda JSON log envelope that the {@code lambda-log-exporter} readers query
 * with {@code filter level = "INFO" and message like /DurableExecutionTelemetry/} and then parse {@code message}.
 */
class LambdaLogExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    /** The reader's Logs Insights {@code message like /DurableExecutionTelemetry/} filter. */
    private static final Pattern READER_MESSAGE_FILTER = Pattern.compile("DurableExecutionTelemetry");

    private static String exportAndCapture(DurableExecutionTelemetryRecord record) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            new LambdaLogExporter().export(record);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportPrintsOneEnvelopeLineWhoseMessageIsTheRecord() throws Exception {
        var record = RecordFactory.sample();
        String out = exportAndCapture(record);

        List<String> lines = out.lines().toList();
        assertEquals(1, lines.size(), "exactly one line: " + out);
        String line = lines.get(0);

        // The line parses, with keys in the shared cross-SDK order and no requestId (not available to an exporter).
        Map<String, Object> envelope = MAPPER.readValue(line, Map.class);
        assertEquals(List.of("timestamp", "level", "message"), List.copyOf(envelope.keySet()));
        assertTrue(TIMESTAMP.matcher((String) envelope.get("timestamp")).matches(), line);
        assertEquals("INFO", envelope.get("level"));

        // message is the compact record JSON string, and parses back to the record's render shape.
        String message = (String) envelope.get("message");
        assertEquals(Json.stringify(new LambdaLogExporter().render(record)), message);
        Map<String, Object> parsed = MAPPER.readValue(message, Map.class);
        assertEquals("DurableExecutionTelemetry", parsed.get("recordType"));
        assertEquals(record.executionArn(), parsed.get("executionArn"));
        assertTrue(parsed.containsKey("operationsByName"));
        assertFalse(parsed.containsKey("operations"));

        // The reader's filter: level = "INFO" and message like /DurableExecutionTelemetry/.
        assertTrue("INFO".equals(envelope.get("level"))
                && READER_MESSAGE_FILTER.matcher(message).find());
    }

    @Test
    void timestampAlwaysHasThreeFractionDigitsInUtc() {
        assertEquals(
                "{\"timestamp\":\"2026-10-21T08:00:00.000Z\",\"level\":\"INFO\",\"message\":\"{}\"}",
                LambdaLogExporter.logLine("{}", Instant.parse("2026-10-21T08:00:00Z")));
        assertTrue(LambdaLogExporter.logLine("{}", Instant.parse("2026-10-21T08:00:00.123456789Z"))
                .startsWith("{\"timestamp\":\"2026-10-21T08:00:00.123Z\""));
    }

    @Test
    void truncationIsMeasuredOnTheRecordNotTheEnvelope() {
        var record = RecordFactory.sample();
        var exporter = new LambdaLogExporter();
        int recordBytes = Json.byteSize(exporter.render(record));

        // A limit equal to the rendered record keeps it whole, even though the printed envelope line is larger.
        var shaped = Truncation.truncateRecord(record, recordBytes, exporter::render);
        assertSame(record, shaped);
        assertFalse(shaped.toWireMap().containsKey("truncated"));
        String line = exportAndCapture(shaped).strip();
        assertTrue(line.getBytes(StandardCharsets.UTF_8).length > recordBytes, line);
    }
}
