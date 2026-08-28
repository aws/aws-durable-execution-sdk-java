// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse;
import software.amazon.lambda.durable.insight.exporters.CloudWatchLogsExporter;

class CloudWatchLogsExporterTest {

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.executionArn = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
        r.status = "SUCCEEDED";
        r.startTime = "2026-08-05T00:00:00Z";
        r.operations()
                .add(new OperationRecord()
                        .id("op-1")
                        .name("greet")
                        .type("STEP")
                        .subType("Step")
                        .status("SUCCEEDED"));
        return r;
    }

    @Test
    void createsStreamOnceAndPutsOperationsByNameEvent() {
        CloudWatchLogsClient client = mock(CloudWatchLogsClient.class);
        when(client.createLogStream(any(CreateLogStreamRequest.class)))
                .thenReturn(CreateLogStreamResponse.builder().build());
        when(client.putLogEvents(any(PutLogEventsRequest.class)))
                .thenReturn(PutLogEventsResponse.builder().build());

        CloudWatchLogsExporter exporter = CloudWatchLogsExporter.builder()
                .logGroupName("/my/group")
                .client(client)
                .build();

        exporter.export(sampleRecord());
        exporter.export(sampleRecord());

        // stream created once (cached), events put twice
        verify(client, times(1)).createLogStream(any(CreateLogStreamRequest.class));
        ArgumentCaptor<PutLogEventsRequest> put = ArgumentCaptor.forClass(PutLogEventsRequest.class);
        verify(client, times(2)).putLogEvents(put.capture());

        PutLogEventsRequest req = put.getValue();
        assertEquals("/my/group", req.logGroupName());
        String message = req.logEvents().get(0).message();
        assertTrue(message.contains("operationsByName"), "CloudWatch emits the by-name map");
        assertTrue(!message.contains("\"operations\""), "CloudWatch must not emit the canonical array");
    }
}
