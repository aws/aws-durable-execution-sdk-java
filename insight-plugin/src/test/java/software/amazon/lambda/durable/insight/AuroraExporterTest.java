// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.rdsdata.model.Field;
import software.amazon.awssdk.services.rdsdata.model.SqlParameter;
import software.amazon.lambda.durable.insight.exporters.AuroraExporter;

class AuroraExporterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.emittedAt = "2026-07-15T12:00:00.000Z";
        r.executionArn = ARN;
        r.functionName = "fn";
        r.status = "RUNNING";
        r.startTime = "2026-07-15T11:59:00.000Z";
        r.addOperation(
                new OperationRecord().id("op-1").name("fetch-user").type("STEP").status("SUCCEEDED"));
        return r;
    }

    private static ExecuteStatementRequest export(AuroraExporter.Builder builder, WorkflowInsightRecord record) {
        RdsDataClient client = mock(RdsDataClient.class);
        when(client.executeStatement(any(ExecuteStatementRequest.class)))
                .thenReturn(ExecuteStatementResponse.builder().build());
        builder.client(client).build().export(record);
        ArgumentCaptor<ExecuteStatementRequest> req = ArgumentCaptor.forClass(ExecuteStatementRequest.class);
        verify(client).executeStatement(req.capture());
        return req.getValue();
    }

    private static Map<String, Field> params(ExecuteStatementRequest req) {
        Map<String, Field> out = new LinkedHashMap<>();
        for (SqlParameter p : req.parameters()) {
            out.put(p.name(), p.value());
        }
        return out;
    }

    private static AuroraExporter.Builder base() {
        return AuroraExporter.builder()
                .resourceArn("arn:aws:rds:us-east-1:123456789012:cluster:c")
                .secretArn("arn:aws:secretsmanager:us-east-1:123456789012:secret:s")
                .database("insight");
    }

    @Test
    void postgresUpsertBindsEveryColumnWithNullsForAbsentFields() {
        WorkflowInsightRecord record = sampleRecord();
        ExecuteStatementRequest req = export(base().engine(AuroraExporter.Engine.POSTGRESQL), record);

        assertEquals("arn:aws:rds:us-east-1:123456789012:cluster:c", req.resourceArn());
        assertEquals("arn:aws:secretsmanager:us-east-1:123456789012:secret:s", req.secretArn());
        assertEquals("insight", req.database());
        assertTrue(req.sql().startsWith("INSERT INTO workflow_insight"));
        assertTrue(req.sql().contains("ON CONFLICT (execution_arn) DO UPDATE SET"));
        assertTrue(req.sql().contains(":record_json::jsonb"));

        Map<String, Field> p = params(req);
        assertEquals(9, p.size());
        assertEquals(ARN, p.get("execution_arn").stringValue());
        assertTrue(p.get("execution_name").isNull());
        assertEquals("fn", p.get("function_name").stringValue());
        assertEquals("RUNNING", p.get("status").stringValue());
        assertEquals("2026-07-15T11:59:00.000Z", p.get("start_time").stringValue());
        assertTrue(p.get("end_time").isNull());
        assertTrue(p.get("duration_ms").isNull());
        assertEquals("2026-07-15T12:00:00.000Z", p.get("emitted_at").stringValue());
        assertEquals(Json.stringify(record.toWireMap()), p.get("record_json").stringValue());
        assertTrue(p.get("record_json").stringValue().contains("\"operations\":["));
    }

    @Test
    void mysqlUpsertBindsCompletedFields() {
        WorkflowInsightRecord record = sampleRecord();
        record.executionName = "exec-1";
        record.status = "SUCCEEDED";
        record.endTime = "2026-07-15T12:00:00.000Z";
        record.durationMs = 60_000L;
        ExecuteStatementRequest req =
                export(base().engine(AuroraExporter.Engine.MYSQL).table("insight_rows"), record);

        assertTrue(req.sql().startsWith("INSERT INTO insight_rows"));
        assertTrue(req.sql().contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(req.sql().contains("::timestamptz"));

        Map<String, Field> p = params(req);
        assertEquals("exec-1", p.get("execution_name").stringValue());
        assertEquals("2026-07-15T12:00:00.000Z", p.get("end_time").stringValue());
        assertEquals(60_000L, p.get("duration_ms").longValue());
    }

    @Test
    void rejectsUnsafeTableNameAtBuildTime() {
        assertThrows(IllegalArgumentException.class, () -> base().engine(AuroraExporter.Engine.MYSQL)
                .table("t; DROP TABLE x")
                .build());
        assertThrows(NullPointerException.class, () -> base().build(), "engine is required");
        assertEquals(
                1_000_000, base().engine(AuroraExporter.Engine.MYSQL).build().maxRecordSizeBytes());
    }

    @Test
    void engineParsesConfigurationStrings() {
        assertEquals(AuroraExporter.Engine.POSTGRESQL, AuroraExporter.Engine.fromValue("postgresql"));
        assertEquals(AuroraExporter.Engine.MYSQL, AuroraExporter.Engine.fromValue("mysql"));
        assertThrows(IllegalArgumentException.class, () -> AuroraExporter.Engine.fromValue("oracle"));
    }
}
