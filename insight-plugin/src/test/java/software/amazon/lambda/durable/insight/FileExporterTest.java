// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.insight.exporters.FileExporter;

/** Exercises {@link FileExporter} against a real filesystem (no mocked IO). */
class FileExporterTest {

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.executionArn = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
        r.executionName = "exec-1";
        r.functionName = "f";
        r.status = "SUCCEEDED";
        r.emittedAt = "2026-08-05T12:34:56Z";
        r.startTime = "2026-08-05T00:00:00Z";
        r.addOperation(new OperationRecord()
                .id("op-1")
                .name("greet")
                .type("STEP")
                .subType("Step")
                .status("SUCCEEDED"));
        return r;
    }

    @Test
    void ndjsonAppendsDatePartitionedCompactLine(@TempDir Path dir) throws Exception {
        FileExporter exporter = FileExporter.builder().directory(dir).build();

        exporter.export(sampleRecord());
        exporter.export(sampleRecord());

        Path file = dir.resolve("2026-08-05.ndjson");
        assertTrue(Files.exists(file), "date-partitioned ndjson file should exist");
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "each export appends one line");
        for (String line : lines) {
            assertFalse(line.isBlank());
            assertFalse(line.contains("\n  "), "ndjson lines are compact, not pretty-printed");
            assertTrue(line.contains("\"operations\""), "array format is the default");
            assertFalse(line.contains("operationsByName"));
            assertTrue(line.contains("\"greet\""));
        }
    }

    @Test
    void jsonModeWritesOnePrettyFilePerExecutionAndOverwrites(@TempDir Path dir) throws Exception {
        FileExporter exporter = FileExporter.builder()
                .directory(dir)
                .mode(FileExporter.Mode.JSON)
                .build();

        exporter.export(sampleRecord());
        exporter.export(sampleRecord()); // second update overwrites, not appends

        Path file = dir.resolve("exec-1.json");
        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        assertTrue(content.contains("\n  "), "json mode is pretty-printed with 2-space indent");
        assertTrue(content.contains("\"executionName\" : \"exec-1\""));
        // Overwrite (not append): the file parses as a single JSON object.
        assertEquals('{', content.trim().charAt(0));
        assertEquals('}', content.trim().charAt(content.trim().length() - 1));
    }

    @Test
    void jsonModeFallsBackToArnWhenExecutionNameNull(@TempDir Path dir) throws Exception {
        WorkflowInsightRecord r = sampleRecord();
        r.executionName = null;
        FileExporter exporter = FileExporter.builder()
                .directory(dir)
                .mode(FileExporter.Mode.JSON)
                .build();

        exporter.export(r);

        // The ARN contains ':' '/' '$' — all sanitized to '_'.
        String expected = "arn_aws_lambda_us-west-2_1_function_f__LATEST_durable-execution_exec-1_invocation-1.json";
        assertTrue(Files.exists(dir.resolve(expected)), "unsafe ARN chars are sanitized into the file name");
    }

    @Test
    void byNameFormatReplacesArrayWithMap(@TempDir Path dir) throws Exception {
        FileExporter exporter = FileExporter.builder()
                .directory(dir)
                .operationsFormat(FileExporter.OperationsFormat.BY_NAME)
                .build();

        exporter.export(sampleRecord());

        String line = Files.readAllLines(dir.resolve("2026-08-05.ndjson")).get(0);
        assertTrue(line.contains("operationsByName"));
        assertFalse(line.contains("\"operations\":"), "by-name replaces the array");
        assertFalse(line.contains("\"operations\" :"));
    }

    @Test
    void bothFormatEmitsArrayAndByNameMap(@TempDir Path dir) throws Exception {
        FileExporter exporter = FileExporter.builder()
                .directory(dir)
                .operationsFormat(FileExporter.OperationsFormat.BOTH)
                .build();

        exporter.export(sampleRecord());

        String line = Files.readAllLines(dir.resolve("2026-08-05.ndjson")).get(0);
        assertTrue(line.contains("\"operations\""));
        assertTrue(line.contains("operationsByName"));
    }

    @Test
    void createsNestedDirectoryWhenMissing(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("a/b/c");
        FileExporter exporter = FileExporter.builder().directory(nested).build();

        exporter.export(sampleRecord());

        assertTrue(Files.exists(nested.resolve("2026-08-05.ndjson")));
    }

    @Test
    void missingEmittedAtFallsBackToTodayPartition(@TempDir Path dir) throws Exception {
        WorkflowInsightRecord r = sampleRecord();
        r.emittedAt = null;
        FileExporter exporter = FileExporter.builder().directory(dir).build();

        exporter.export(r);

        String today = java.time.Instant.now()
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .toString();
        assertTrue(Files.exists(dir.resolve(today + ".ndjson")));
    }

    @Test
    void maxRecordSizeBytesDefaultsToNull(@TempDir Path dir) {
        FileExporter exporter = FileExporter.builder().directory(dir).build();
        assertEquals(null, exporter.maxRecordSizeBytes());
    }

    @Test
    void maxRecordSizeBytesIsReportedWhenSet(@TempDir Path dir) {
        FileExporter exporter =
                FileExporter.builder().directory(dir).maxRecordSizeBytes(512).build();
        assertEquals(512, exporter.maxRecordSizeBytes());
    }

    @Test
    void builderRejectsMissingDirectory() {
        assertThrows(
                IllegalArgumentException.class, () -> FileExporter.builder().build());
    }

    @Test
    void builderRejectsBlankDirectory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FileExporter.builder().directory("   ").build());
    }

    @Test
    void builderRejectsNonPositiveMaxRecordSize(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> FileExporter.builder()
                .directory(dir)
                .maxRecordSizeBytes(0)
                .build());
    }

    @Test
    void flushIsNoOpAndDoesNotThrow(@TempDir Path dir) {
        FileExporter exporter = FileExporter.builder().directory(dir).build();
        exporter.flush();
    }
}
