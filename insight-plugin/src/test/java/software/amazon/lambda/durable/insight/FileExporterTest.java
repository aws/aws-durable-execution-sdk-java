// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.insight.exporters.FileExporter;
import software.amazon.lambda.durable.insight.exporters.OperationsFormat;

class FileExporterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.emittedAt = "2026-07-15T12:00:00.000Z";
        r.executionArn = ARN;
        r.functionName = "fn";
        r.status = "SUCCEEDED";
        r.startTime = "2026-07-15T11:59:00.000Z";
        r.addOperation(
                new OperationRecord().id("op-1").name("fetch-user").type("STEP").status("SUCCEEDED"));
        return r;
    }

    @Test
    void appendsDatePartitionedNdjsonLinesAndCreatesTheDirectory() throws Exception {
        Path dir = tempDir.resolve("nested/insight");
        FileExporter exporter = FileExporter.builder().directory(dir.toString()).build();
        WorkflowInsightRecord first = sampleRecord();
        WorkflowInsightRecord second = sampleRecord();
        second.status = "FAILED";
        exporter.export(first);
        exporter.export(second);

        Path file = dir.resolve("2026-07-15.ndjson");
        assertTrue(Files.exists(file));
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "append, not overwrite");
        assertEquals(Json.stringify(first.toWireMap()), lines.get(0));
        assertEquals(Json.stringify(second.toWireMap()), lines.get(1));
        assertTrue(Files.readString(file).endsWith("\n"));
        assertTrue(MAPPER.readTree(lines.get(0)).get("operations").isArray());
        assertNull(exporter.maxRecordSizeBytes(), "no default size limit");
    }

    @Test
    void jsonModeWritesOnePrettyFilePerExecutionAndOverwrites() throws Exception {
        FileExporter exporter = FileExporter.builder()
                .directory(tempDir.toString())
                .mode(FileExporter.Mode.JSON)
                .operationsFormat(OperationsFormat.BY_NAME)
                .build();
        WorkflowInsightRecord record = sampleRecord();
        record.executionName = "my exec/1";
        record.input = Map.of("empty", List.of());
        exporter.export(record);
        record.status = "FAILED";
        exporter.export(record);

        Path file = tempDir.resolve("my_exec_1.json");
        String content = Files.readString(file);
        assertTrue(content.startsWith("{\n  \"recordType\": \"WorkflowInsight\",\n"), content);
        assertTrue(content.contains("\"empty\": []"), content);
        assertFalse(content.contains(" : "), "no space before the colon");
        JsonNode parsed = MAPPER.readTree(content);
        assertEquals("FAILED", parsed.get("status").asText(), "second export overwrote the first");
        assertTrue(parsed.has("operationsByName"));
        assertFalse(parsed.has("operations"));
        assertEquals(1, Files.list(tempDir).count());
    }

    @Test
    void concurrentLargeRecordsAppendWholeLines() throws Exception {
        FileExporter exporter =
                FileExporter.builder().directory(tempDir.toString()).build();
        int threads = 8;
        int perThread = 5;
        String payload = "x".repeat(40_000); // several append chunks per line
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        WorkflowInsightRecord r = sampleRecord();
                        r.input = Map.of("payload", payload);
                        exporter.export(r);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> lines = Files.readAllLines(tempDir.resolve("2026-07-15.ndjson"));
        assertEquals(threads * perThread, lines.size());
        for (String line : lines) {
            assertEquals(
                    payload, MAPPER.readTree(line).get("input").get("payload").asText());
        }
    }

    @Test
    void jsonModeFallsBackToTheArnWhenThereIsNoExecutionName() {
        FileExporter.builder()
                .directory(tempDir.toString())
                .mode(FileExporter.Mode.JSON)
                .build()
                .export(sampleRecord());
        assertTrue(Files.exists(tempDir.resolve("arn_aws_lambda_us-east-1_123456789012_function_fn__LATEST.json")));
        assertEquals(FileExporter.Mode.JSON, FileExporter.Mode.fromValue("json"));
    }
}
