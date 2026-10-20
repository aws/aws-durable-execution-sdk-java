// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.SqlParameter;
import software.amazon.lambda.durable.telemetry.exporters.RedshiftExporter;

class RedshiftExporterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";

    private DurableExecutionTelemetryRecord sampleRecord() {
        DurableExecutionTelemetryRecord r = new DurableExecutionTelemetryRecord();
        r.emittedAt = "2026-07-15T12:00:00.000Z";
        r.executionArn = ARN;
        r.functionName = "fn";
        r.status = "RUNNING";
        r.startTime = "2026-07-15T11:59:00.000Z";
        r.addOperation(
                new OperationRecord().id("op-1").name("fetch-user").type("STEP").status("SUCCEEDED"));
        return r;
    }

    private static ExecuteStatementRequest export(
            RedshiftExporter.Builder builder, DurableExecutionTelemetryRecord record) {
        RedshiftDataClient client = mock(RedshiftDataClient.class);
        when(client.executeStatement(any(ExecuteStatementRequest.class)))
                .thenReturn(ExecuteStatementResponse.builder().build());
        builder.client(client).build().export(record);
        ArgumentCaptor<ExecuteStatementRequest> req = ArgumentCaptor.forClass(ExecuteStatementRequest.class);
        verify(client).executeStatement(req.capture());
        return req.getValue();
    }

    private static Map<String, String> params(ExecuteStatementRequest req) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SqlParameter p : req.parameters()) {
            out.put(p.name(), p.value());
        }
        return out;
    }

    @Test
    void serverlessMergeUsesTypedNullLiteralsForAbsentFields() {
        DurableExecutionTelemetryRecord record = sampleRecord();
        ExecuteStatementRequest req =
                export(RedshiftExporter.builder().workgroupName("wg").database("telemetry"), record);

        assertEquals("wg", req.workgroupName());
        assertNull(req.clusterIdentifier());
        assertEquals("telemetry", req.database());
        assertNull(req.dbUser());
        assertNull(req.secretArn());
        assertTrue(req.sql().startsWith("MERGE INTO public.durable_execution_telemetry USING ("));
        assertTrue(req.sql().contains("NULL::varchar AS execution_name"));
        assertTrue(req.sql().contains("NULL::timestamptz AS end_time"));
        assertTrue(req.sql().contains("NULL::bigint AS duration_ms"));
        assertTrue(req.sql().contains("JSON_PARSE(:record_json) AS record_json"));
        assertTrue(req.sql().contains("ON public.durable_execution_telemetry.execution_arn = src.execution_arn"));

        Map<String, String> p = params(req);
        assertEquals(6, p.size());
        assertEquals(ARN, p.get("execution_arn"));
        assertEquals("fn", p.get("function_name"));
        assertEquals("RUNNING", p.get("status"));
        assertEquals("2026-07-15T11:59:00.000Z", p.get("start_time"));
        assertEquals("2026-07-15T12:00:00.000Z", p.get("emitted_at"));
        assertEquals(Json.stringify(record.toWireMap()), p.get("record_json"));
    }

    @Test
    void provisionedMergeBindsCompletedFieldsAndCustomSchemaTable() {
        DurableExecutionTelemetryRecord record = sampleRecord();
        record.executionName = "exec-1";
        record.status = "SUCCEEDED";
        record.endTime = "2026-07-15T12:00:00.000Z";
        record.durationMs = 60_000L;
        ExecuteStatementRequest req = export(
                RedshiftExporter.builder()
                        .clusterIdentifier("cluster-1")
                        .database("telemetry")
                        .secretArn("arn:aws:secretsmanager:us-east-1:123456789012:secret:s")
                        .schema("analytics")
                        .table("wf"),
                record);

        assertEquals("cluster-1", req.clusterIdentifier());
        assertNull(req.dbUser());
        assertEquals("arn:aws:secretsmanager:us-east-1:123456789012:secret:s", req.secretArn());
        assertTrue(req.sql().startsWith("MERGE INTO analytics.wf USING ("));
        assertTrue(req.sql().contains(":execution_name::varchar AS execution_name"));
        assertTrue(req.sql().contains(":end_time::timestamptz AS end_time"));
        assertTrue(req.sql().contains(":duration_ms::bigint AS duration_ms"));
        assertFalse(req.sql().contains("NULL::"));

        Map<String, String> p = params(req);
        assertEquals(9, p.size());
        assertEquals("2026-07-15T12:00:00.000Z", p.get("end_time"));
        assertEquals("60000", p.get("duration_ms"));
        assertEquals("exec-1", p.get("execution_name"));
    }

    @Test
    void omitsStartTimeParameterWhenTheRecordHasNone() {
        DurableExecutionTelemetryRecord record = sampleRecord();
        record.startTime = null;
        ExecuteStatementRequest req =
                export(RedshiftExporter.builder().workgroupName("wg").database("telemetry"), record);

        assertTrue(req.sql().contains("NULL::timestamptz AS start_time"));
        assertFalse(req.sql().contains(":start_time"));
        assertFalse(params(req).containsKey("start_time"));
        assertEquals(5, req.parameters().size());
    }

    @Test
    void validatesTargetAndIdentifiersAtBuildTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RedshiftExporter.builder().database("telemetry").build(),
                "workgroupName or clusterIdentifier is required");
        assertThrows(
                IllegalArgumentException.class,
                () -> RedshiftExporter.builder()
                        .workgroupName("wg")
                        .clusterIdentifier("cluster-1")
                        .database("telemetry")
                        .build(),
                "workgroupName and clusterIdentifier are exclusive");
        assertThrows(
                IllegalArgumentException.class,
                () -> RedshiftExporter.builder()
                        .clusterIdentifier("cluster-1")
                        .database("telemetry")
                        .dbUser("admin")
                        .secretArn("arn:aws:secretsmanager:us-east-1:123456789012:secret:s")
                        .build(),
                "dbUser and secretArn are exclusive");
        assertEquals(
                "admin",
                export(
                                RedshiftExporter.builder()
                                        .clusterIdentifier("cluster-1")
                                        .database("telemetry")
                                        .dbUser("admin"),
                                sampleRecord())
                        .dbUser());
        assertThrows(
                IllegalArgumentException.class,
                () -> RedshiftExporter.builder()
                        .workgroupName("wg")
                        .database("telemetry")
                        .schema("public; DROP")
                        .build());
        assertEquals(
                1_000_000,
                RedshiftExporter.builder()
                        .workgroupName("wg")
                        .database("telemetry")
                        .build()
                        .maxRecordSizeBytes());
    }
}
