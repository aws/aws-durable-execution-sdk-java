// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.lambda.durable.insight.exporters.OperationsFormat;
import software.amazon.lambda.durable.insight.exporters.SQSExporter;

class SQSExporterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";
    private static final String STANDARD = "https://sqs.us-east-1.amazonaws.com/123456789012/insight";
    private static final String FIFO = STANDARD + ".fifo";

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

    private static SendMessageRequest export(SQSExporter.Builder builder, WorkflowInsightRecord record) {
        SqsClient client = mock(SqsClient.class);
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().build());
        builder.client(client).build().export(record);
        ArgumentCaptor<SendMessageRequest> req = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(req.capture());
        return req.getValue();
    }

    @Test
    void sendsStandardQueueMessageWithAttributesAndNoFifoFields() {
        WorkflowInsightRecord record = sampleRecord();
        SendMessageRequest req = export(SQSExporter.builder().queueUrl(STANDARD), record);

        assertEquals(STANDARD, req.queueUrl());
        assertNull(req.messageGroupId());
        assertNull(req.messageDeduplicationId());
        assertEquals("String", req.messageAttributes().get("status").dataType());
        assertEquals("SUCCEEDED", req.messageAttributes().get("status").stringValue());
        assertEquals("fn", req.messageAttributes().get("functionName").stringValue());
        assertEquals(Json.stringify(record.toWireMap()), req.messageBody());
    }

    @Test
    void setsFifoGroupAndDeduplicationIds() {
        SendMessageRequest req = export(SQSExporter.builder().queueUrl(FIFO), sampleRecord());
        assertEquals(ARN, req.messageGroupId());
        assertEquals(ARN + ":2026-07-15T12:00:00.000Z", req.messageDeduplicationId());
    }

    @Test
    void boundsFifoIdsToOneHundredTwentyEightCharacters() {
        WorkflowInsightRecord record = sampleRecord();
        record.executionArn = "arn:aws:lambda:us-east-1:123456789012:function:" + "f".repeat(80)
                + ":$LATEST/durable-execution/" + "e".repeat(40) + "/1";
        SendMessageRequest req = export(SQSExporter.builder().queueUrl(FIFO), record);

        assertEquals(64, req.messageGroupId().length(), "digest replaces an over-long default group id");
        assertEquals(64, req.messageDeduplicationId().length());
        assertTrue(req.messageDeduplicationId().matches("[0-9a-f]{64}"));
        assertEquals(
                req.messageDeduplicationId(),
                export(SQSExporter.builder().queueUrl(FIFO), record).messageDeduplicationId(),
                "same record yields the same deduplication id");

        String longGroup = "g".repeat(129);
        SendMessageRequest custom = export(SQSExporter.builder().queueUrl(FIFO).messageGroupId(longGroup), record);
        assertEquals(64, custom.messageGroupId().length(), "explicit group ids are bounded too");
        assertEquals(
                "g".repeat(128),
                export(SQSExporter.builder().queueUrl(FIFO).messageGroupId("g".repeat(128)), record)
                        .messageGroupId());
    }

    @Test
    void honorsExplicitGroupIdAndByNameFormat() {
        SendMessageRequest req = export(
                SQSExporter.builder()
                        .queueUrl(FIFO)
                        .messageGroupId("custom-group")
                        .operationsFormat(OperationsFormat.BY_NAME),
                sampleRecord());
        assertEquals("custom-group", req.messageGroupId());
        assertTrue(req.messageBody().contains("\"operationsByName\""));
        assertFalse(req.messageBody().contains("\"operations\""));
        assertEquals(
                256_000,
                SQSExporter.builder()
                        .queueUrl(STANDARD)
                        .client(mock(SqsClient.class))
                        .build()
                        .maxRecordSizeBytes());
    }
}
