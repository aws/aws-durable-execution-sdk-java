// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.lambda.durable.insight.exporters.OpenSearchExporter;

class OpenSearchExporterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";
    private static final String ENCODED_ARN =
            "arn%3Aaws%3Alambda%3Aus-east-1%3A123456789012%3Afunction%3Afn%3A%24LATEST";

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

    @Test
    void putsSignedDocumentKeyedByEncodedArn() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            WorkflowInsightRecord record = sampleRecord();
            OpenSearchExporter.builder()
                    .endpoint(server.url("/"))
                    .region("us-east-1")
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKID", "secret")))
                    .build()
                    .export(record);

            LocalHttpServer.Captured req = server.only();
            assertEquals("PUT", req.method);
            assertEquals("/workflow-insight/_doc/" + ENCODED_ARN, req.path);
            assertEquals("application/json", req.headers.getFirst("Content-Type"));
            String authorization = req.headers.getFirst("Authorization");
            assertNotNull(authorization);
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKID/"), authorization);
            assertTrue(authorization.contains("/us-east-1/es/aws4_request"), authorization);
            assertNotNull(req.headers.getFirst("X-Amz-Date"));
            assertEquals(Json.stringify(record.toWireMap()), req.body);
        }
    }

    @Test
    void usesBasicAuthWithoutSigning() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            OpenSearchExporter.builder()
                    .endpoint(server.url(""))
                    .auth(OpenSearchExporter.Auth.BASIC)
                    .username("admin")
                    .password("secret")
                    .indexName("custom-index")
                    .build()
                    .export(sampleRecord());

            LocalHttpServer.Captured req = server.only();
            assertEquals("/custom-index/_doc/" + ENCODED_ARN, req.path);
            String expected =
                    "Basic " + Base64.getEncoder().encodeToString("admin:secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, req.headers.getFirst("Authorization"));
            assertEquals(null, req.headers.getFirst("X-Amz-Date"));
        }
    }

    @Test
    void throwsWithStatusAndDetailOnFailure() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.status = 403;
            OpenSearchExporter exporter = OpenSearchExporter.builder()
                    .endpoint(server.url(""))
                    .auth(OpenSearchExporter.Auth.BASIC)
                    .username("u")
                    .password("p")
                    .build();
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> exporter.export(sampleRecord()));
            assertTrue(e.getMessage().contains("403"), e.getMessage());
            assertTrue(e.getMessage().contains("denied"), e.getMessage());
            assertEquals(10_000_000, exporter.maxRecordSizeBytes());
        }
    }

    @Test
    void sigv4RequiresRegionAtBuildTime() {
        assertThrows(NullPointerException.class, () -> OpenSearchExporter.builder()
                .endpoint("https://d.us-east-1.es.amazonaws.com")
                .build());
        assertThrows(NullPointerException.class, () -> OpenSearchExporter.builder()
                .endpoint("https://d.us-east-1.es.amazonaws.com")
                .auth(OpenSearchExporter.Auth.BASIC)
                .password("p")
                .build());
        assertThrows(NullPointerException.class, () -> OpenSearchExporter.builder()
                .endpoint("https://d.us-east-1.es.amazonaws.com")
                .auth(OpenSearchExporter.Auth.BASIC)
                .username("u")
                .build());
        assertEquals(OpenSearchExporter.Auth.BASIC, OpenSearchExporter.Auth.fromValue("basic"));
        assertThrows(IllegalArgumentException.class, () -> OpenSearchExporter.Auth.fromValue("oauth"));
    }
}
