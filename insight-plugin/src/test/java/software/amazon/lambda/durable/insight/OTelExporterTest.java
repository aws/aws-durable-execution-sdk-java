// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.insight.exporters.OTelExporter;
import software.amazon.lambda.durable.insight.exporters.OperationsFormat;

class OTelExporterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.emittedAt = "2026-07-15T12:00:00.000Z";
        r.executionArn = ARN;
        r.executionName = "exec-1";
        r.functionName = "fn";
        r.functionQualifier = "$LATEST";
        r.region = "us-east-1";
        r.accountId = "123456789012";
        r.status = "SUCCEEDED";
        r.startTime = "2026-07-15T11:59:00.000Z";
        r.durationMs = 60_000L;
        r.addOperation(
                new OperationRecord().id("op-1").name("fetch-user").type("STEP").status("SUCCEEDED"));
        return r;
    }

    private static JsonNode attr(JsonNode attributes, String key) {
        for (JsonNode a : attributes) {
            if (key.equals(a.get("key").asText())) {
                return a.get("value");
            }
        }
        throw new AssertionError("missing attribute " + key);
    }

    @Test
    void postsOneOtlpLogRecordWithResourceAndLogAttributes() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            OTelExporter exporter = OTelExporter.builder()
                    .endpoint(server.url("/v1/logs"))
                    .headers(Map.of("x-api-key", "k1"))
                    .build();
            exporter.export(sampleRecord());

            LocalHttpServer.Captured req = server.only();
            assertEquals("POST", req.method);
            assertEquals("/v1/logs", req.path);
            assertEquals("application/json", req.headers.getFirst("Content-Type"));
            assertEquals("k1", req.headers.getFirst("x-api-key"));

            JsonNode payload = MAPPER.readTree(req.body);
            JsonNode resourceLogs = payload.get("resourceLogs").get(0);
            JsonNode resourceAttrs = resourceLogs.get("resource").get("attributes");
            assertEquals(
                    "fn", attr(resourceAttrs, "service.name").get("stringValue").asText());
            assertEquals(
                    "us-east-1",
                    attr(resourceAttrs, "cloud.region").get("stringValue").asText());
            assertEquals(
                    "123456789012",
                    attr(resourceAttrs, "cloud.account.id").get("stringValue").asText());
            assertEquals(
                    "$LATEST",
                    attr(resourceAttrs, "faas.version").get("stringValue").asText());

            JsonNode scopeLogs = resourceLogs.get("scopeLogs").get(0);
            assertEquals("1.0", scopeLogs.get("scope").get("version").asText());
            JsonNode log = scopeLogs.get("logRecords").get(0);
            assertEquals("1784116800000000000", log.get("timeUnixNano").asText());
            assertEquals(9, log.get("severityNumber").asInt());
            assertEquals("SUCCEEDED", log.get("severityText").asText());
            JsonNode logAttrs = log.get("attributes");
            assertEquals(
                    ARN,
                    attr(logAttrs, "workflow.execution_arn").get("stringValue").asText());
            assertEquals(
                    "60000",
                    attr(logAttrs, "workflow.duration_ms").get("intValue").asText());

            JsonNode body = MAPPER.readTree(log.get("body").get("stringValue").asText());
            assertEquals(ARN, body.get("executionArn").asText());
            assertTrue(body.get("operations").isArray());
        }
    }

    @Test
    void absentFieldsRenderEmptyValuesAndByNameBody() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            WorkflowInsightRecord record = sampleRecord();
            record.region = null;
            record.executionName = null;
            record.durationMs = null;
            record.status = "FAILED";
            OTelExporter exporter = OTelExporter.builder()
                    .endpoint(server.url("/v1/logs"))
                    .operationsFormat(OperationsFormat.BY_NAME)
                    .build();
            exporter.export(record);

            JsonNode payload = MAPPER.readTree(server.only().body);
            JsonNode resourceLogs = payload.get("resourceLogs").get(0);
            assertEquals(
                    0,
                    attr(resourceLogs.get("resource").get("attributes"), "cloud.region")
                            .size());
            JsonNode log =
                    resourceLogs.get("scopeLogs").get(0).get("logRecords").get(0);
            assertEquals(17, log.get("severityNumber").asInt());
            assertEquals(
                    "",
                    attr(log.get("attributes"), "workflow.execution_name")
                            .get("stringValue")
                            .asText());
            assertEquals(
                    "0",
                    attr(log.get("attributes"), "workflow.duration_ms")
                            .get("intValue")
                            .asText());
            JsonNode body = MAPPER.readTree(log.get("body").get("stringValue").asText());
            assertTrue(body.has("operationsByName"));
            assertTrue(!body.has("operations"));
        }
    }

    @Test
    void renderMeasuresTheWholeOtlpEnvelope() {
        OTelExporter exporter =
                OTelExporter.builder().endpoint("http://127.0.0.1:1/v1/logs").build();
        assertEquals(1_000_000, exporter.maxRecordSizeBytes());
        Map<?, ?> rendered = (Map<?, ?>) exporter.render(sampleRecord());
        assertTrue(rendered.containsKey("resourceLogs"));
    }

    @Test
    void nonSuccessStatusThrows() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.status = 503;
            OTelExporter exporter =
                    OTelExporter.builder().endpoint(server.url("/v1/logs")).build();
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> exporter.export(sampleRecord()));
            assertTrue(e.getMessage().contains("503"));
        }
    }

    @Test
    void protobufIsRejectedAtBuildTime() {
        assertThrows(IllegalArgumentException.class, () -> OTelExporter.builder()
                .endpoint("http://127.0.0.1:1/v1/logs")
                .protocol(OTelExporter.Protocol.HTTP_PROTOBUF)
                .build());
        assertEquals(OTelExporter.Protocol.HTTP_JSON, OTelExporter.Protocol.fromValue("http/json"));
        assertThrows(IllegalArgumentException.class, () -> OTelExporter.Protocol.fromValue("grpc"));
    }
}
