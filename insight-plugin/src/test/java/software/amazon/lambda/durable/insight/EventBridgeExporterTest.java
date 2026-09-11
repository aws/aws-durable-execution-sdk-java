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

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;
import software.amazon.lambda.durable.insight.exporters.EventBridgeExporter;
import software.amazon.lambda.durable.insight.exporters.OperationsFormat;

class EventBridgeExporterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";

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

    private static EventBridgeClient clientReturning(PutEventsResponse response) {
        EventBridgeClient client = mock(EventBridgeClient.class);
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(response);
        return client;
    }

    private static PutEventsRequestEntry capture(EventBridgeClient client) {
        ArgumentCaptor<PutEventsRequest> req = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(client).putEvents(req.capture());
        assertEquals(1, req.getValue().entries().size());
        return req.getValue().entries().get(0);
    }

    @Test
    void publishesOneEventWithStatusDetailTypeAndRecordDetail() {
        EventBridgeClient client =
                clientReturning(PutEventsResponse.builder().failedEntryCount(0).build());
        WorkflowInsightRecord record = sampleRecord();
        EventBridgeExporter.builder().client(client).build().export(record);

        PutEventsRequestEntry entry = capture(client);
        assertEquals("default", entry.eventBusName());
        assertEquals("aws.durable-execution.insight", entry.source());
        assertEquals("SUCCEEDED", entry.detailType());
        assertEquals(Instant.parse("2026-07-15T12:00:00.000Z"), entry.time());
        assertEquals(Json.stringify(record.toWireMap()), entry.detail());
        assertTrue(entry.detail().contains("\"operations\":["));
    }

    @Test
    void honorsCustomBusSourceAndByNameFormat() {
        EventBridgeClient client = clientReturning(PutEventsResponse.builder().build());
        EventBridgeExporter.builder()
                .eventBusName("insight-bus")
                .source("my.source")
                .operationsFormat(OperationsFormat.BY_NAME)
                .client(client)
                .build()
                .export(sampleRecord());

        PutEventsRequestEntry entry = capture(client);
        assertEquals("insight-bus", entry.eventBusName());
        assertEquals("my.source", entry.source());
        assertTrue(entry.detail().contains("\"operationsByName\""));
        assertFalse(entry.detail().contains("\"operations\""));
    }

    @Test
    void throwsWhenPutEventsReportsAFailedEntry() {
        EventBridgeClient client = clientReturning(PutEventsResponse.builder()
                .failedEntryCount(1)
                .entries(PutEventsResultEntry.builder()
                        .errorCode("ThrottlingException")
                        .errorMessage("slow down")
                        .build())
                .build());
        EventBridgeExporter exporter =
                EventBridgeExporter.builder().client(client).build();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> exporter.export(sampleRecord()));
        assertTrue(e.getMessage().contains("ThrottlingException"));
        assertTrue(e.getMessage().contains("slow down"));
        assertEquals(256_000, exporter.maxRecordSizeBytes());
    }
}
