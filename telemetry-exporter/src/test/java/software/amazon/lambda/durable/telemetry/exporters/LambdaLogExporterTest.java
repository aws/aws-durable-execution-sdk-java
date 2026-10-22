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
        String recordJson = Json.stringify(exporter.render(shaped));
        assertEquals(
                recordBytes + ENVELOPE_BYTES + escapedChars(recordJson),
                line.getBytes(StandardCharsets.UTF_8).length,
                "line = record + 68-byte envelope + one byte per quote or backslash in the record");
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
     * An escape-heavy record just under the default 256,000-byte budget is not truncated, and its printed line is about
     * twice the record: at most {@code 2 × maxRecordSizeBytes + 68}, and under the 1 MB CloudWatch Logs event limit.
     */
    @Test
    void escapeHeavyRecordNearTheLimitDoublesButStaysUnderOneMegabyte() {
        var exporter = new LambdaLogExporter();
        int limit = exporter.maxRecordSizeBytes();
        for (char ch : new char[] {'"', '\\'}) {
            var record = RecordFactory.sampleWithInput(String.valueOf(ch).repeat(127_000));
            int recordBytes = Json.byteSize(exporter.render(record));
            assertTrue(recordBytes <= limit && recordBytes > limit - 2_000, "record near the limit: " + recordBytes);
            var shaped = Truncation.truncateRecord(record, limit, exporter::render);
            assertSame(record, shaped, "not truncated");
            int lineBytes = exportAndCapture(shaped).strip().getBytes(StandardCharsets.UTF_8).length;
            assertTrue(lineBytes > 2 * recordBytes - 1_000, "line about twice the record: " + lineBytes);
            assertTrue(lineBytes <= 2 * limit + ENVELOPE_BYTES, "line within 2 x limit + 68: " + lineBytes);
            assertTrue(lineBytes < 1_048_576, "line under the 1 MB CloudWatch Logs event limit: " + lineBytes);
        }
    }
}
