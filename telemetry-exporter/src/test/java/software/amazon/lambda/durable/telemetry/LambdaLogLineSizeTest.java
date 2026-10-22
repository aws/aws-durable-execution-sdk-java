// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.telemetry.exporters.LambdaLogExporter;

/** Size checks for the {@code LambdaLogExporter} line through the plugin's export path. */
class LambdaLogLineSizeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Exports through the plugin's path (truncation, then export) and returns the printed line. */
    private static String exportThroughPlugin(LambdaLogExporter exporter, DurableExecutionTelemetryRecord record) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            DurableExecutionTelemetry.exportRecord(record, exporter);
        } finally {
            System.setOut(original);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertEquals(1, out.lines().count(), "exactly one line");
        return out.strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> messageOf(String line) throws Exception {
        Map<String, Object> envelope = MAPPER.readValue(line, Map.class);
        return MAPPER.readValue((String) envelope.get("message"), Map.class);
    }

    private static int bytes(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length;
    }

    /** {@code count} succeeded operations named {@code op-<i>}, one second apart, each with {@code result}. */
    private static DurableExecutionTelemetryRecord withResults(int count, Object result) {
        DurableExecutionTelemetryRecord r = RecordFactory.sample();
        r.operations.clear();
        Instant start = Instant.parse("2026-07-15T11:00:00Z");
        for (int i = 0; i < count; i++) {
            r.addOperation(new OperationRecord()
                    .id("id-" + i)
                    .name("op-" + i)
                    .type("STEP")
                    .status("SUCCEEDED")
                    .startTime(start.plusSeconds(i).toString())
                    .result(result));
        }
        return r;
    }

    /**
     * Coarse cost guard, independent of timing: 3,000 operations, each with a 400-quote result, at the default limit.
     * The pass measures the line only a few dozen times rather than once per dropped result and operation, and the
     * printed line is within the limit and counts every dropped operation.
     */
    @Test
    @SuppressWarnings("unchecked")
    void manyEscapeHeavyResultsNeedFewMeasurements() throws Exception {
        var exporter = new LambdaLogExporter();
        var record = withResults(3_000, "\"".repeat(400));
        AtomicInteger measurements = new AtomicInteger();
        var shaped = Truncation.truncateMeasured(record, exporter.maxRecordSizeBytes(), r -> {
            measurements.incrementAndGet();
            return exporter.renderedSize(r);
        });
        assertTrue(measurements.get() <= 100, measurements + " measurements; one per step would be thousands");
        assertTrue(exporter.renderedSize(shaped) <= 256_000);

        String line = exportThroughPlugin(exporter, record);
        assertTrue(bytes(line) <= 256_000, "line within the default limit: " + bytes(line));
        Map<String, Object> parsed = messageOf(line);
        int kept = ((Map<String, Object>) parsed.get("operationsByName")).size();
        assertEquals(3_000, kept + (Integer) parsed.get("droppedOperations"));
        assertEquals(Json.stringify(shaped.toByNameWireMap()), (String)
                MAPPER.readValue(line, Map.class).get("message"));
    }

    /**
     * Skipping checks does not change the outcome: on records with repeated names (whose summaries change when one
     * occurrence is dropped), small and large results, failed operations with causes, and input and output, the
     * measured pass returns the same record as measuring {@code render} after every step.
     */
    @Test
    void measuredPassMatchesMeasuringEveryStep() {
        var exporter = new LambdaLogExporter();
        Instant start = Instant.parse("2026-07-15T11:00:00Z");
        for (int limit : new int[] {600, 2_000, 9_000, 30_000, 120_000}) {
            DurableExecutionTelemetryRecord r = RecordFactory.sampleWithError("boom \"x\"", "io");
            r.operations.clear();
            r.input = "\\".repeat(3_000);
            r.output = Map.of("k", "v".repeat(2_000));
            for (int i = 0; i < 240; i++) {
                var op = new OperationRecord()
                        .id("id-" + i)
                        .name(i % 5 == 0 ? "op-" + i : "group-" + (i % 7))
                        .type(i % 3 == 0 ? "STEP" : "WAIT")
                        .status(i % 4 == 0 ? "FAILED" : "SUCCEEDED")
                        .startTime(i % 11 == 0 ? null : start.plusSeconds(i).toString())
                        .durationMs((long) i * 997)
                        .attempt(i % 3 + 1)
                        .result(i % 6 == 0 ? Integer.valueOf(i) : "\"\\é😀".repeat(i % 50));
                if (i % 4 == 0) {
                    op.error(new ErrorInfo("E" + i, "m\u0001".repeat(i), new ErrorInfo("C", "c")));
                }
                r.addOperation(op);
            }
            var measured = Truncation.truncateMeasured(r.deepCopy(), limit, exporter::renderedSize);
            var everyStep = Truncation.truncateRecord(r.deepCopy(), limit, exporter::render);
            assertEquals(Json.stringify(everyStep.toWireMap()), Json.stringify(measured.toWireMap()), "limit " + limit);
        }
    }

    /** Rule 7: a 70,000-character name is capped at 8,192 code points plus an ellipsis, on the line too. */
    @Test
    @SuppressWarnings("unchecked")
    void longNameIsCapped() throws Exception {
        String name = "N".repeat(70_000);
        DurableExecutionTelemetryRecord r = RecordFactory.sample();
        r.status = "FAILED";
        r.error = new ErrorInfo(name, "m", new ErrorInfo(name, "c"));
        assertEquals("N".repeat(8_192) + "\u2026", r.error.name());
        assertEquals(r.error.name(), r.error.cause().name());

        String line = exportThroughPlugin(new LambdaLogExporter(), r);
        Map<String, Object> error = (Map<String, Object>) messageOf(line).get("error");
        assertEquals("N".repeat(8_192) + "\u2026", error.get("name"));
    }

    /** Long identity fields, at the lengths Lambda allows and beyond, for the size bound tests. */
    private static DurableExecutionTelemetryRecord worstCaseRecord(String name, String message) {
        DurableExecutionTelemetryRecord r = RecordFactory.sample();
        r.functionName = "f".repeat(64);
        r.functionQualifier = "q".repeat(128);
        r.executionName = "e".repeat(256);
        r.executionArn = "arn:aws:lambda:us-east-1:123456789012:function:" + "a".repeat(190);
        r.region = "ap-southeast-5";
        r.accountId = "123456789012";
        r.status = "FAILED";
        r.endTime = "2026-07-15T12:00:00.000Z";
        r.durationMs = 3_600_000L;
        ErrorInfo chain = null;
        for (int i = 0; i < 16; i++) {
            chain = new ErrorInfo(name, message, chain);
        }
        r.error = new ErrorInfo(name, message, chain);
        r.operations.clear();
        for (int i = 0; i < 50; i++) {
            r.addOperation(new OperationRecord()
                    .id("id-" + i)
                    .name("op-" + i)
                    .type("STEP")
                    .status("FAILED")
                    .startTime(
                            Instant.parse("2026-07-15T11:00:00Z").plusSeconds(i).toString())
                    .result(message)
                    .error(new ErrorInfo(name, message, new ErrorInfo(name, message))));
        }
        r.input = message.repeat(20);
        r.output = message.repeat(20);
        return r;
    }

    /**
     * The 64 KB bound: with an 8,192-character message of the costliest characters (each takes up to 7 bytes of line)
     * and a name under 500 characters, once causes, operations, input and output are dropped the line fits 64,000
     * bytes, even with long identity fields.
     */
    @Test
    void worstCaseMessageFits64KB() throws Exception {
        for (String ch : List.of("\u0001", "\\", "\"", "\uD83D\uDE00", "\u2028", "\u00E9")) {
            String message = ch.repeat(9_000);
            String name = "\u0001".repeat(499); // the costliest name under 500 characters
            for (int limit : new int[] {64_000, 65_536}) {
                String line = exportThroughPlugin(new LambdaLogExporter(limit), worstCaseRecord(name, message));
                assertTrue(bytes(line) <= limit, "U+" + Integer.toHexString(ch.codePointAt(0)) + ": " + bytes(line));
                Map<String, Object> parsed = messageOf(line);
                assertEquals(Boolean.TRUE, parsed.get("droppedCauses"));
                assertEquals(50, parsed.get("droppedOperations"));
                assertEquals(Boolean.TRUE, parsed.get("droppedInput"));
                assertEquals(Boolean.TRUE, parsed.get("droppedOutput"));
            }
        }
    }

    /**
     * When the name and the message are both 8,192 of the costliest characters, the error alone can take about 115 KB
     * of line, so 64 KB cannot be met; the line still fits any limit of 128,000 bytes or more, including the default.
     */
    @Test
    void worstCaseNameAndMessageFit128KB() throws Exception {
        for (String ch : List.of("\u0001", "\\", "\"", "\uD83D\uDE00")) {
            String text = ch.repeat(9_000);
            for (int limit : new int[] {128_000, 256_000}) {
                String line = exportThroughPlugin(new LambdaLogExporter(limit), worstCaseRecord(text, text));
                assertTrue(bytes(line) <= limit, "U+" + Integer.toHexString(ch.codePointAt(0)) + ": " + bytes(line));
                assertNull(messageOf(line).get("input"));
            }
        }
        String control = "\u0001".repeat(9_000);
        String line = exportThroughPlugin(new LambdaLogExporter(64_000), worstCaseRecord(control, control));
        assertTrue(bytes(line) > 64_000, "over 64 KB, as documented: " + bytes(line));
    }
}
