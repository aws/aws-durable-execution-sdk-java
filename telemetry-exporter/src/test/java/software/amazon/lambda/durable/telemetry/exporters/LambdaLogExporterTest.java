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
        return exportAndCapture(new LambdaLogExporter(), record);
    }

    private static String exportAndCapture(LambdaLogExporter exporter, DurableExecutionTelemetryRecord record) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            exporter.export(record);
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
    void lineThatFitsIsPrintedWhole() {
        var record = RecordFactory.sample();
        var exporter = new LambdaLogExporter();
        String line = exportAndCapture(record).strip();
        String recordJson = Json.stringify(exporter.render(record));
        assertEquals(
                Json.byteSize(exporter.render(record)) + ENVELOPE_BYTES + escapedChars(recordJson),
                line.getBytes(StandardCharsets.UTF_8).length,
                "line = record + 68-byte envelope + one byte per quote or backslash in the record");
        assertFalse(line.contains("\\\"truncated\\\""), "not truncated: " + line);
    }

    /** Bytes the envelope adds around an empty {@code message}. */
    private static final int ENVELOPE_BYTES = 68;

    private static long escapedChars(String json) {
        return json.chars().filter(c -> c == '"' || c == '\\').count();
    }

    @Test
    void envelopeAddsSixtyEightBytes() {
        String empty = LambdaLogExporter.logLine("", Instant.parse("2026-10-21T08:00:00Z"));
        assertEquals(ENVELOPE_BYTES, empty.getBytes(StandardCharsets.UTF_8).length, empty);
    }

    /**
     * The limit caps the printed line, escaping included. An escape-heavy record that fits the record budget but whose
     * line does not (300,000 backslashes are about 600 KB of record and 1.2 MB of line) is truncated again until the
     * line fits; its message still parses and says it was truncated.
     */
    @Test
    @SuppressWarnings("unchecked")
    void escapeHeavyRecordIsTruncatedUntilThePrintedLineFits() throws Exception {
        for (int limit : new int[] {256_000, 1_000_000}) {
            var exporter = new LambdaLogExporter(limit);
            for (char ch : new char[] {'"', '\\'}) {
                int count = limit == 1_000_000 ? 300_000 : 127_000;
                var record = RecordFactory.sampleWithInput(String.valueOf(ch).repeat(count));
                int recordBytes = Json.byteSize(exporter.render(record));
                assertTrue(recordBytes <= limit, "record fits the record budget: " + recordBytes);
                var shaped = Truncation.truncateRecord(record, limit, exporter::render);
                assertSame(record, shaped, "record-level truncation keeps it whole");

                String line = exportAndCapture(exporter, shaped).strip();
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                assertTrue(lineBytes <= limit, limit + ": printed line within the limit: " + lineBytes);
                Map<String, Object> envelope = MAPPER.readValue(line, Map.class);
                Map<String, Object> parsed = MAPPER.readValue((String) envelope.get("message"), Map.class);
                assertEquals(Boolean.TRUE, parsed.get("truncated"), "record says it was truncated");
                assertEquals(record.executionArn(), parsed.get("executionArn"));
            }
        }
    }
}
