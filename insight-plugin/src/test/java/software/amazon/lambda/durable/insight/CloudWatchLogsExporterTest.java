// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        r.addOperation(new OperationRecord()
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

    @Test
    void concurrentExportsShareStreamCacheWithoutCollectionRace() throws Exception {
        CloudWatchLogsClient client = mock(CloudWatchLogsClient.class);
        when(client.createLogStream(any(CreateLogStreamRequest.class)))
                .thenReturn(CreateLogStreamResponse.builder().build());
        when(client.putLogEvents(any(PutLogEventsRequest.class)))
                .thenReturn(PutLogEventsResponse.builder().build());

        CloudWatchLogsExporter exporter = CloudWatchLogsExporter.builder()
                .logGroupName("/my/group")
                .client(client)
                .build();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        exporter.export(sampleRecord()); // all target the same date-based stream, racing ensureStream
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            ready.await();
            go.countDown(); // release all threads at once to maximize contention on the shared set
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "no thread saw a collection race or error: " + errors);
        // Every export emitted exactly one PutLogEvents; createLogStream is bounded by the thread count and called at
        // least once. A thread-unsafe HashSet could corrupt the table or spin here.
        verify(client, times(threads)).putLogEvents(any(PutLogEventsRequest.class));
        verify(client, atLeast(1)).createLogStream(any(CreateLogStreamRequest.class));
        verify(client, atMost(threads)).createLogStream(any(CreateLogStreamRequest.class));

        // Cache is now populated: a subsequent export creates no new stream and still emits its event.
        clearInvocations(client);
        exporter.export(sampleRecord());
        verify(client, times(0)).createLogStream(any(CreateLogStreamRequest.class));
        verify(client, times(1)).putLogEvents(any(PutLogEventsRequest.class));
    }
}
