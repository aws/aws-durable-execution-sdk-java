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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.lambda.durable.insight.exporters.SQSExporter;

class SQSExporterTest {

    private static final String STANDARD_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/insight";
    private static final String FIFO_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/insight.fifo";
    private static final String EXEC_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST/durable-execution/exec-1/invocation-1";

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.executionArn = EXEC_ARN;
        r.executionName = "exec-1";
        r.functionName = "fn";
        r.status = "SUCCEEDED";
        r.startTime = "2026-08-05T00:00:00Z";
        r.emittedAt = "2026-08-05T00:00:01Z";
        r.addOperation(new OperationRecord()
                .id("op-1")
                .name("greet")
                .type("STEP")
                .subType("Step")
                .status("SUCCEEDED"));
        return r;
    }

    private static SqsClient mockClient() {
        SqsClient client = mock(SqsClient.class);
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().build());
        return client;
    }

    private static SendMessageRequest capture(SqsClient client) {
        ArgumentCaptor<SendMessageRequest> req = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(req.capture());
        return req.getValue();
    }

    @Test
    void standardQueueSendsAttributesAndArrayBodyWithoutFifoFields() {
        SqsClient client = mockClient();
        SQSExporter exporter =
                SQSExporter.builder().queueUrl(STANDARD_URL).client(client).build();

        exporter.export(sampleRecord());

        SendMessageRequest req = capture(client);
        assertEquals(STANDARD_URL, req.queueUrl());
        assertNull(req.messageGroupId(), "standard queue carries no message group id");
        assertNull(req.messageDeduplicationId(), "standard queue carries no dedup id");
        assertEquals("SUCCEEDED", req.messageAttributes().get("status").stringValue());
        assertEquals("fn", req.messageAttributes().get("functionName").stringValue());
        assertEquals("String", req.messageAttributes().get("status").dataType());
        assertTrue(req.messageBody().contains("\"operations\""), "array format emits the operations array");
        assertFalse(req.messageBody().contains("operationsByName"), "array format omits the by-name map");
        assertTrue(req.messageBody().contains("\"greet\""));
    }

    @Test
    void fifoQueueDefaultsGroupIdToExecutionArnAndDerivesDedupId() {
        SqsClient client = mockClient();
        SQSExporter exporter =
                SQSExporter.builder().queueUrl(FIFO_URL).client(client).build();

        exporter.export(sampleRecord());

        SendMessageRequest req = capture(client);
        assertEquals(EXEC_ARN, req.messageGroupId());
        assertEquals(EXEC_ARN + ":2026-08-05T00:00:01Z", req.messageDeduplicationId());
    }

    @Test
    void fifoQueueHonorsExplicitMessageGroupId() {
        SqsClient client = mockClient();
        SQSExporter exporter = SQSExporter.builder()
                .queueUrl(FIFO_URL)
                .messageGroupId("custom-group")
                .client(client)
                .build();

        exporter.export(sampleRecord());

        assertEquals("custom-group", capture(client).messageGroupId());
    }

    @Test
    void byNameFormatEmitsOperationsByNameMap() {
        SqsClient client = mockClient();
        SQSExporter exporter = SQSExporter.builder()
                .queueUrl(STANDARD_URL)
                .operationsFormat(SQSExporter.OperationsFormat.BY_NAME)
                .client(client)
                .build();

        exporter.export(sampleRecord());

        String body = capture(client).messageBody();
        assertTrue(body.contains("operationsByName"), "by-name format emits the map");
        assertFalse(body.contains("\"operations\":["), "by-name format replaces the array");
    }

    @Test
    void bothFormatEmitsArrayAndByNameMap() {
        SqsClient client = mockClient();
        SQSExporter exporter = SQSExporter.builder()
                .queueUrl(STANDARD_URL)
                .operationsFormat(SQSExporter.OperationsFormat.BOTH)
                .client(client)
                .build();

        exporter.export(sampleRecord());

        String body = capture(client).messageBody();
        assertTrue(body.contains("\"operations\":["), "both format keeps the array");
        assertTrue(body.contains("operationsByName"), "both format also adds the map");
    }

    @Test
    void defaultMaxRecordSizeIs256Kb() {
        SQSExporter exporter = SQSExporter.builder()
                .queueUrl(STANDARD_URL)
                .client(mockClient())
                .build();
        assertEquals(256_000, exporter.maxRecordSizeBytes());
    }

    @Test
    void builderRejectsMissingQueueUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SQSExporter.builder().client(mockClient()).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> SQSExporter.builder().queueUrl("").client(mockClient()).build());
    }

    @Test
    void sendFailurePropagatesToCaller() {
        SqsClient client = mock(SqsClient.class);
        when(client.sendMessage(any(SendMessageRequest.class))).thenThrow(new RuntimeException("send failed"));
        SQSExporter exporter =
                SQSExporter.builder().queueUrl(STANDARD_URL).client(client).build();

        RuntimeException e = assertThrows(RuntimeException.class, () -> exporter.export(sampleRecord()));
        assertEquals("send failed", e.getMessage());
    }
}
