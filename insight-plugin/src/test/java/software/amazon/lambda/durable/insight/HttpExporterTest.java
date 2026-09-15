// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.insight.exporters.HttpExporter;
import software.amazon.lambda.durable.insight.exporters.OperationsFormat;

class HttpExporterTest {

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.emittedAt = "2026-07-15T12:00:00.000Z";
        r.executionArn = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";
        r.functionName = "fn";
        r.status = "SUCCEEDED";
        r.startTime = "2026-07-15T11:59:00.000Z";
        r.addOperation(
                new OperationRecord().id("op-1").name("fetch-user").type("STEP").status("SUCCEEDED"));
        return r;
    }

    @Test
    void postsJsonWithContentTypeAndCustomHeaders() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            WorkflowInsightRecord record = sampleRecord();
            HttpExporter exporter = HttpExporter.builder()
                    .url(server.url("/hook"))
                    .headers(Map.of("Authorization", "Bearer t0k"))
                    .build();
            exporter.export(record);

            LocalHttpServer.Captured req = server.only();
            assertEquals("POST", req.method);
            assertEquals("/hook", req.path);
            assertEquals("application/json", req.headers.getFirst("Content-Type"));
            assertEquals("Bearer t0k", req.headers.getFirst("Authorization"));
            assertEquals(Json.stringify(record.toWireMap()), req.body);
            assertNull(exporter.maxRecordSizeBytes(), "no default size limit");
        }
    }

    @Test
    void putsByNameBodyWhenConfigured() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            HttpExporter.builder()
                    .url(server.url("/records/1"))
                    .method(HttpExporter.Method.PUT)
                    .operationsFormat(OperationsFormat.BY_NAME)
                    .build()
                    .export(sampleRecord());

            LocalHttpServer.Captured req = server.only();
            assertEquals("PUT", req.method);
            assertTrue(req.body.contains("\"operationsByName\""));
            assertFalse(req.body.contains("\"operations\""));
        }
    }

    @Test
    void nonSuccessStatusThrows() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.status = 500;
            HttpExporter exporter =
                    HttpExporter.builder().url(server.url("/hook")).build();
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> exporter.export(sampleRecord()));
            assertTrue(e.getMessage().contains("500"));
        }
    }

    @Test
    void timesOutWhenTheEndpointIsSlow() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.delayMillis = 2_000;
            HttpExporter exporter = HttpExporter.builder()
                    .url(server.url("/hook"))
                    .timeoutMs(200L)
                    .build();
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> exporter.export(sampleRecord()));
            assertInstanceOf(HttpTimeoutException.class, e.getCause());
        }
    }

    @Test
    void methodParsesConfigurationStrings() {
        assertEquals(HttpExporter.Method.PUT, HttpExporter.Method.fromValue("PUT"));
        assertThrows(IllegalArgumentException.class, () -> HttpExporter.Method.fromValue("PATCH"));
        assertThrows(NullPointerException.class, () -> HttpExporter.builder().build());
    }
}
