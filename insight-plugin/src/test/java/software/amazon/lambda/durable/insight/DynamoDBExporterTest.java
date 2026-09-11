// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.lambda.durable.insight.exporters.DynamoDBExporter;

class DynamoDBExporterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.emittedAt = "2026-07-15T12:00:00.000Z";
        r.executionArn = ARN;
        r.executionName = "exec-1";
        r.functionName = "fn";
        r.status = "SUCCEEDED";
        r.startTime = "2026-07-15T11:59:00.000Z";
        r.durationMs = 60_000L;
        r.input = Map.of("k", "v", "n", 2, "flag", true);
        r.addOperation(new OperationRecord()
                .id("op-1")
                .name("fetch-user")
                .type("STEP")
                .subType("Step")
                .status("SUCCEEDED")
                .durationMs(5L)
                .attempt(1));
        return r;
    }

    private static PutItemRequest export(DynamoDBExporter.Builder builder) {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());
        builder.client(client).build().export(new DynamoDBExporterTest().sampleRecord());
        ArgumentCaptor<PutItemRequest> req = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(req.capture());
        return req.getValue();
    }

    @Test
    void writesHistoryItemKeyedByArnAndEmittedAt() {
        PutItemRequest req = export(DynamoDBExporter.builder().tableName("insight"));
        Map<String, AttributeValue> item = req.item();

        assertEquals("insight", req.tableName());
        assertEquals(ARN, item.get("pk").s());
        assertEquals("2026-07-15T12:00:00.000Z", item.get("sk").s());
        assertEquals("SUCCEEDED", item.get("status").s());
        assertEquals("60000", item.get("durationMs").n());
        assertEquals("v", item.get("input").m().get("k").s());
        assertEquals("2", item.get("input").m().get("n").n());
        assertTrue(item.get("input").m().get("flag").bool());
        assertNull(item.get("operations"), "the by-name rendering replaces the operations array");
        Map<String, AttributeValue> byName = item.get("operationsByName").m();
        assertEquals("1", byName.get("fetch-user").m().get("count").n());
        assertEquals("STEP", byName.get("fetch-user").m().get("type").s());
    }

    @Test
    void upsertsWithoutSortKeyAndHonorsCustomPartitionKey() {
        PutItemRequest req = export(DynamoDBExporter.builder()
                .tableName("insight")
                .partitionKey("executionArnKey")
                .sortKey(""));
        Map<String, AttributeValue> item = req.item();

        assertEquals(ARN, item.get("executionArnKey").s());
        assertFalse(item.containsKey("pk"));
        assertFalse(item.containsKey("sk"));
    }

    @Test
    void renderIsTheByNameMapAndDefaultLimitIsFourHundredKilobytes() {
        DynamoDBExporter exporter = DynamoDBExporter.builder()
                .tableName("insight")
                .client(mock(DynamoDbClient.class))
                .build();
        assertEquals(400_000, exporter.maxRecordSizeBytes());
        Map<?, ?> rendered = (Map<?, ?>) exporter.render(sampleRecord());
        assertTrue(rendered.containsKey("operationsByName"));
        assertFalse(rendered.containsKey("operations"));
    }

    @Test
    void requiresTableName() {
        assertThrows(
                NullPointerException.class, () -> DynamoDBExporter.builder().build());
    }
}
