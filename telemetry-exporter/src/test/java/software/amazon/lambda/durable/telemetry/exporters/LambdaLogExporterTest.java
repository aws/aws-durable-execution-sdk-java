// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry.exporters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * The {@code LambdaLogExporter} line is the Lambda JSON log envelope. Readers query it with {@code filter level =
 * "INFO" and message like /DurableExecutionTelemetry/} and then parse {@code message}.
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
        assertEquals(Json.stringify(record.toByNameWireMap()), message);
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
    @SuppressWarnings("unchecked")
    void renderIsThePrintedEnvelope() throws Exception {
        var record = RecordFactory.sample();
        var exporter = new LambdaLogExporter();
        Map<String, Object> rendered = (Map<String, Object>) exporter.render(record);
        assertEquals(List.of("timestamp", "level", "message"), List.copyOf(rendered.keySet()));
        String line = exportAndCapture(record).strip();
        Map<String, Object> printed = MAPPER.readValue(line, Map.class);
        assertEquals(rendered.get("message"), printed.get("message"));
        assertEquals(Json.byteSize(rendered), line.getBytes(StandardCharsets.UTF_8).length, "same size as measured");
    }

    @Test
    void lineThatFitsIsPrintedWhole() {
        var record = RecordFactory.sample();
        String line = exportAndCapture(record).strip();
        String recordJson = Json.stringify(record.toByNameWireMap());
        assertEquals(
                Json.byteSize(record.toByNameWireMap()) + ENVELOPE_BYTES + escapedChars(recordJson),
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

    /** Runs the one truncation pass the plugin runs before export, then exports, and returns the printed line. */
    private static String shapeAndExport(LambdaLogExporter exporter, DurableExecutionTelemetryRecord record) {
        var shaped = Truncation.truncateRecord(record, exporter.maxRecordSizeBytes(), exporter::render);
        String out = exportAndCapture(exporter, shaped);
        assertEquals(1, out.lines().count(), "exactly one line");
        return out.strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> messageOf(String line) throws Exception {
        Map<String, Object> envelope = MAPPER.readValue(line, Map.class);
        return MAPPER.readValue((String) envelope.get("message"), Map.class);
    }

    /**
     * Review B1: with the default 256,000-byte limit, an execution error whose message is 100,000 backslashes (about
     * 400 KB of line uncapped) prints a line within the limit. The message cap does it; the cause is dropped first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void defaultLimitHoldsForAnEscapeHeavyExecutionError() throws Exception {
        var exporter = new LambdaLogExporter();
        String backslashes = "\\".repeat(100_000);
        for (String input : new String[] {"small", "\\".repeat(100_000)}) {
            var record = RecordFactory.sampleWithError(backslashes, backslashes);
            record = RecordFactory.withInput(record, input);
            String line = shapeAndExport(exporter, record);
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(lineBytes <= 256_000, "printed line within the default limit: " + lineBytes);

            Map<String, Object> parsed = messageOf(line);
            Map<String, Object> error = (Map<String, Object>) parsed.get("error");
            assertEquals("\\".repeat(8_192) + "\u2026", error.get("message"), "message capped");
            assertEquals(record.executionArn(), parsed.get("executionArn"));
            if (input.length() > 5) {
                // The cause is dropped first, then input.
                assertEquals(Boolean.TRUE, parsed.get("truncated"));
                assertEquals(Boolean.TRUE, parsed.get("droppedCauses"));
                assertFalse(error.containsKey("cause"));
                assertEquals(Boolean.TRUE, parsed.get("droppedInput"));
            } else {
                // Capped messages fit without truncation, so the cause is kept.
                assertNull(parsed.get("truncated"));
                assertEquals("\\".repeat(8_192) + "\u2026", ((Map<String, Object>) error.get("cause")).get("message"));
            }
        }
    }

    /**
     * Review B2: one pass, measured on the printed line, reports every dropped operation. 400 failed operations whose
     * messages are full of quotes, limit 100,000.
     */
    @Test
    @SuppressWarnings("unchecked")
    void droppedOperationsIsAccurateWhenTheLineIsMeasured() throws Exception {
        var exporter = new LambdaLogExporter(100_000);
        var record = RecordFactory.sampleWithFailedOperations(400, "\"".repeat(200));
        String line = shapeAndExport(exporter, record);
        assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 100_000);

        Map<String, Object> parsed = messageOf(line);
        int kept = ((Map<String, Object>) parsed.get("operationsByName")).size();
        int dropped = (Integer) parsed.get("droppedOperations");
        assertTrue(kept > 0 && dropped > 0, kept + " kept, " + dropped + " dropped");
        assertEquals(400, kept + dropped, "every dropped operation is counted");
        assertEquals(Boolean.TRUE, parsed.get("droppedCauses"));
    }

    /**
     * The limit caps the printed line, escaping included: an escape-heavy input that fits the record budget but whose
     * line does not is dropped in the one pass, and the message still parses.
     */
    @Test
    void escapeHeavyInputIsDroppedSoThePrintedLineFits() throws Exception {
        for (int limit : new int[] {256_000, 1_000_000}) {
            var exporter = new LambdaLogExporter(limit);
            for (char ch : new char[] {'"', '\\'}) {
                int count = limit == 1_000_000 ? 300_000 : 127_000;
                var record = RecordFactory.sampleWithInput(String.valueOf(ch).repeat(count));
                assertTrue(Json.byteSize(record.toByNameWireMap()) <= limit, "record alone fits");

                String line = shapeAndExport(exporter, record);
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                assertTrue(lineBytes <= limit, limit + ": printed line within the limit: " + lineBytes);
                Map<String, Object> parsed = messageOf(line);
                assertEquals(Boolean.TRUE, parsed.get("truncated"), "record says it was truncated");
                assertEquals(Boolean.TRUE, parsed.get("droppedInput"));
                assertEquals(record.executionArn(), parsed.get("executionArn"));
            }
        }
    }

    /**
     * A limit smaller than the envelope overhead cannot be met: the execution error and identity fields are never
     * dropped. The line is still printed whole and parses, with everything droppable dropped and the markers set.
     */
    @Test
    @SuppressWarnings("unchecked")
    void limitBelowTheEnvelopeOverheadPrintsTheMinimalLine() throws Exception {
        var exporter = new LambdaLogExporter(50);
        var record = RecordFactory.withInput(RecordFactory.sampleWithError("boom", "io"), "in");
        String line = shapeAndExport(exporter, record);
        assertTrue(line.getBytes(StandardCharsets.UTF_8).length > 50, "over the limit, by design");

        Map<String, Object> parsed = messageOf(line);
        assertEquals(Boolean.TRUE, parsed.get("truncated"));
        assertEquals(Boolean.TRUE, parsed.get("droppedCauses"));
        assertEquals(1, parsed.get("droppedOperations"));
        assertEquals(Boolean.TRUE, parsed.get("droppedInput"));
        assertEquals(Map.of(), parsed.get("operationsByName"));
        assertEquals("boom", ((Map<String, Object>) parsed.get("error")).get("message"), "error kept");
        assertEquals(record.executionArn(), parsed.get("executionArn"));
    }

    /** Control characters and non-ASCII text round-trip through the two levels of escaping. */
    @Test
    @SuppressWarnings("unchecked")
    void controlAndNonAsciiCharactersRoundTrip() throws Exception {
        String text = "tab\t nl\n ctl\u0001\u001F é 日本 \uD83D\uDE00 \u2028";
        var record = RecordFactory.sampleWithInput(text);
        String line = exportAndCapture(record).strip();
        assertEquals(1, exportAndCapture(record).lines().count());
        assertFalse(line.contains("\u0001"), "control characters are escaped");
        assertEquals(text, messageOf(line).get("input"));
    }
}
