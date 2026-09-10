// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.insight.exporters.HttpExporter;
import software.amazon.lambda.durable.insight.exporters.HttpSender;

class HttpExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.executionArn = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";
        r.executionName = "exec-1";
        r.functionName = "fn";
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

    /** Records the arguments of the last send; returns a configurable status. */
    private static final class RecordingSender implements HttpSender {
        String url;
        String method;
        Map<String, String> headers;
        String body;
        Duration timeout;
        int status = 200;

        @Override
        public Response send(String url, String method, Map<String, String> headers, String body, Duration timeout) {
            this.url = url;
            this.method = method;
            this.headers = headers;
            this.body = body;
            this.timeout = timeout;
            return new Response(status, status == 200 ? "OK" : "Server Error");
        }
    }

    @Test
    void postsRecordAsJsonWithContentTypeByDefault() throws Exception {
        RecordingSender sender = new RecordingSender();
        HttpExporter exporter = HttpExporter.builder()
                .url("https://hook.example/insight")
                .sender(sender)
                .build();

        exporter.export(sampleRecord());

        assertEquals("https://hook.example/insight", sender.url);
        assertEquals("POST", sender.method);
        assertEquals("application/json", sender.headers.get("Content-Type"));
        assertEquals(Duration.ofMillis(10_000), sender.timeout);
        JsonNode body = MAPPER.readTree(sender.body);
        assertEquals(
                "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST",
                body.get("executionArn").asText());
        assertTrue(body.get("operations").isArray(), "array format emits the canonical operations array");
        assertFalse(body.has("operationsByName"), "array format must not emit the by-name map");
    }

    @Test
    void usesPutAndMergesCustomHeadersWhenConfigured() {
        RecordingSender sender = new RecordingSender();
        HttpExporter exporter = HttpExporter.builder()
                .url("https://hook.example/insight")
                .method(HttpExporter.Method.PUT)
                .addHeader("Authorization", "Bearer token123")
                .sender(sender)
                .build();

        exporter.export(sampleRecord());

        assertEquals("PUT", sender.method);
        assertEquals("Bearer token123", sender.headers.get("Authorization"));
        assertEquals("application/json", sender.headers.get("Content-Type"));
    }

    @Test
    void callerContentTypeOverridesDefaultCaseInsensitivelyWithNoDuplicate() {
        RecordingSender sender = new RecordingSender();
        HttpExporter exporter = HttpExporter.builder()
                .url("https://hook.example/insight")
                .addHeader("content-type", "application/json; charset=utf-8")
                .sender(sender)
                .build();

        exporter.export(sampleRecord());

        int contentTypeCount = 0;
        String contentTypeValue = null;
        for (Map.Entry<String, String> h : sender.headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase("content-type")) {
                contentTypeCount++;
                contentTypeValue = h.getValue();
            }
        }
        assertEquals(1, contentTypeCount, "exactly one content-type header regardless of casing: " + sender.headers);
        assertEquals("application/json; charset=utf-8", contentTypeValue, "caller value overrides the default");
    }

    @Test
    void throwsWhenEndpointReturnsNon2xx() {
        RecordingSender sender = new RecordingSender();
        sender.status = 500;
        HttpExporter exporter = HttpExporter.builder()
                .url("https://hook.example/insight")
                .sender(sender)
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> exporter.export(sampleRecord()));
        assertTrue(ex.getMessage().contains("500"), ex.getMessage());
    }

    @Test
    void byNameFormatEmitsOperationsByNameMap() throws Exception {
        RecordingSender sender = new RecordingSender();
        HttpExporter exporter = HttpExporter.builder()
                .url("https://hook.example/insight")
                .operationsFormat(HttpExporter.OperationsFormat.BY_NAME)
                .sender(sender)
                .build();

        exporter.export(sampleRecord());

        JsonNode body = MAPPER.readTree(sender.body);
        assertTrue(body.has("operationsByName"), "by-name format emits the operationsByName map");
        assertTrue(body.get("operationsByName").has("greet"));
        assertFalse(body.has("operations"), "by-name format must not emit the operations array");
    }

    @Test
    void bothFormatEmitsArrayAndByNameMap() throws Exception {
        RecordingSender sender = new RecordingSender();
        HttpExporter exporter = HttpExporter.builder()
                .url("https://hook.example/insight")
                .operationsFormat(HttpExporter.OperationsFormat.BOTH)
                .sender(sender)
                .build();

        exporter.export(sampleRecord());

        JsonNode body = MAPPER.readTree(sender.body);
        assertTrue(body.get("operations").isArray(), "both format keeps the operations array");
        assertTrue(body.get("operationsByName").has("greet"), "both format adds the operationsByName map");
    }

    @Test
    void appliesConfiguredTimeout() {
        RecordingSender sender = new RecordingSender();
        HttpExporter exporter = HttpExporter.builder()
                .url("https://hook.example/insight")
                .timeoutMs(2_500)
                .sender(sender)
                .build();

        exporter.export(sampleRecord());

        assertEquals(Duration.ofMillis(2_500), sender.timeout);
    }

    @Test
    void builderRejectsMissingUrl() {
        assertThrows(
                IllegalArgumentException.class, () -> HttpExporter.builder().build());
    }

    @Test
    void builderRejectsNonHttpScheme() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpExporter.builder().url("ftp://host/path").build());
    }

    @Test
    void builderRejectsNonAbsoluteUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpExporter.builder().url("/relative/path").build());
    }

    @Test
    void builderRejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> HttpExporter.builder()
                .url("https://hook.example/insight")
                .timeoutMs(0)
                .build());
    }

    // --- Real in-process HTTP server tests (exercise the default JDK sender end to end) ---

    @Test
    void postsToRealLocalServerWithDefaultSender() throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try {
            HttpExporter exporter = HttpExporter.builder()
                    .url("http://" + authority(server) + "/insight")
                    .build();

            exporter.export(sampleRecord());

            assertEquals("POST", receivedMethod.get());
            assertEquals("application/json", receivedContentType.get());
            JsonNode body = MAPPER.readTree(receivedBody.get());
            assertEquals("exec-1", body.get("executionName").asText());
            assertTrue(body.get("operations").isArray());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realLocalServer500Throws() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        try {
            HttpExporter exporter = HttpExporter.builder()
                    .url("http://" + authority(server) + "/insight")
                    .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> exporter.export(sampleRecord()));
            assertTrue(ex.getMessage().contains("500"), ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realLocalServerReceivesCustomAuthHeaderAndPutMethod() throws Exception {
        List<String> auth = new CopyOnWriteArrayList<>();
        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header != null) {
                auth.add(header);
            }
            method.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try {
            HttpExporter exporter = HttpExporter.builder()
                    .url("http://" + authority(server) + "/insight")
                    .method(HttpExporter.Method.PUT)
                    .addHeader("Authorization", "Bearer abc")
                    .build();

            exporter.export(sampleRecord());

            assertEquals("PUT", method.get());
            assertEquals(List.of("Bearer abc"), auth);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realLocalServerReceivesSingleContentTypeWhenCallerOverridesCasing() throws Exception {
        List<String> contentTypes = new CopyOnWriteArrayList<>();
        HttpServer server = startServer(exchange -> {
            List<String> received = exchange.getRequestHeaders().get("Content-Type");
            if (received != null) {
                contentTypes.addAll(received);
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try {
            HttpExporter exporter = HttpExporter.builder()
                    .url("http://" + authority(server) + "/insight")
                    .addHeader("content-type", "application/json")
                    .build();

            exporter.export(sampleRecord());

            assertEquals(1, contentTypes.size(), "exactly one Content-Type header on the wire: " + contentTypes);
            assertEquals("application/json", contentTypes.get(0));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultSenderThrowsIllegalStateExceptionWhenConnectionRefused() throws Exception {
        // Reserve then release a loopback port so nothing is listening on it: connecting is refused deterministically.
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            deadPort = socket.getLocalPort();
        }
        HttpExporter exporter = HttpExporter.builder()
                .url("http://127.0.0.1:" + deadPort + "/insight")
                .timeoutMs(2_000) // bounded so a stray listener could never hang the test
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> exporter.export(sampleRecord()));
        assertTrue(ex.getMessage().contains("failed"), ex.getMessage());
        assertTrue(ex.getMessage().contains("127.0.0.1:" + deadPort), ex.getMessage());
    }

    @Test
    void defaultSenderThrowsAndPreservesInterruptWhenExportingThreadInterrupted() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Handler blocks without responding, so the client stays parked waiting for the response headers.
        HttpServer server = startServer(exchange -> {
            requestReceived.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try {
            HttpExporter exporter = HttpExporter.builder()
                    .url("http://" + authority(server) + "/insight")
                    .timeoutMs(30_000)
                    .build();

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean interruptPreserved = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                try {
                    exporter.export(sampleRecord());
                } catch (Throwable t) {
                    thrown.set(t);
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                }
            });
            worker.start();

            assertTrue(requestReceived.await(10, TimeUnit.SECONDS), "server should receive the request first");
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(worker.isAlive(), "worker should return after interruption");
            Throwable t = thrown.get();
            assertTrue(t instanceof IllegalStateException, "expected IllegalStateException, got " + t);
            assertTrue(t.getMessage().contains("interrupted"), t.getMessage());
            assertTrue(interruptPreserved.get(), "interrupt flag must be preserved after interruption");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private static HttpServer startServer(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/insight", exchange -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String authority(HttpServer server) {
        return "127.0.0.1:" + server.getAddress().getPort();
    }
}
