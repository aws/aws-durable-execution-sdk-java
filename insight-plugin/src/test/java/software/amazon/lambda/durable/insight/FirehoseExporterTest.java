// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.PutRecordResponse;
import software.amazon.lambda.durable.insight.exporters.FirehoseExporter;
import software.amazon.lambda.durable.insight.exporters.OperationsFormat;

class FirehoseExporterTest {

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

    private static PutRecordRequest export(FirehoseExporter.Builder builder, WorkflowInsightRecord record) {
        FirehoseClient client = mock(FirehoseClient.class);
        when(client.putRecord(any(PutRecordRequest.class)))
                .thenReturn(PutRecordResponse.builder().build());
        builder.client(client).build().export(record);
        ArgumentCaptor<PutRecordRequest> req = ArgumentCaptor.forClass(PutRecordRequest.class);
        verify(client).putRecord(req.capture());
        return req.getValue();
    }

    @Test
    void putsOneNewlineTerminatedJsonRecord() {
        WorkflowInsightRecord record = sampleRecord();
        PutRecordRequest req = export(FirehoseExporter.builder().deliveryStreamName("insight-stream"), record);

        assertEquals("insight-stream", req.deliveryStreamName());
        String data = req.record().data().asUtf8String();
        assertEquals(Json.stringify(record.toWireMap()) + "\n", data);
        assertTrue(data.contains("\"operations\":["));
    }

    @Test
    void honorsByNameFormatAndDefaultLimit() {
        FirehoseExporter.Builder builder = FirehoseExporter.builder()
                .deliveryStreamName("insight-stream")
                .operationsFormat(OperationsFormat.BY_NAME);
        PutRecordRequest req = export(builder, sampleRecord());
        String data = req.record().data().asUtf8String();
        assertTrue(data.contains("\"operationsByName\""));
        assertFalse(data.contains("\"operations\""));
        assertEquals(
                1_000_000,
                FirehoseExporter.builder()
                        .deliveryStreamName("s")
                        .client(mock(FirehoseClient.class))
                        .build()
                        .maxRecordSizeBytes());
    }
}
