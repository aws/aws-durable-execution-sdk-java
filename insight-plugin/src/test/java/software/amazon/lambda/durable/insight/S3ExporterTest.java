// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.lambda.durable.insight.exporters.S3Exporter;

class S3ExporterTest {

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.executionArn = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
        r.executionName = "exec-1";
        r.functionName = "f";
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
    void putsCanonicalOperationsArrayObjectKeyedByExecutionName() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        S3Exporter exporter = S3Exporter.builder()
                .bucket("my-bucket")
                .partitioning(S3Exporter.Partitioning.NONE)
                .client(client)
                .build();
        exporter.export(sampleRecord());

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(req.capture(), body.capture());

        assertEquals("my-bucket", req.getValue().bucket());
        assertEquals("workflow-insight/exec-1.json", req.getValue().key());
        assertEquals("application/json", req.getValue().contentType());

        String json = readBody(body.getValue());
        assertTrue(json.contains("\"operations\""), "S3 emits the canonical operations array");
        assertTrue(!json.contains("operationsByName"), "S3 must not emit the by-name map");
        assertTrue(json.contains("\"greet\""));
    }

    private static String readBody(RequestBody body) throws Exception {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
