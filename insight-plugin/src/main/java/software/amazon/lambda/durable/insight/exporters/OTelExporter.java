// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records as OpenTelemetry log records over OTLP/HTTP JSON. Each record becomes one log record
 * with identity fields as resource and log attributes and the full record JSON as the body. Compatible with any
 * OTLP-capable backend; authentication is via headers.
 */
@Experimental
public final class OTelExporter implements InsightExporter {

    /** OTLP transport encoding. */
    @Experimental
    public enum Protocol {
        HTTP_JSON("http/json"),
        HTTP_PROTOBUF("http/protobuf");

        private final String value;

        Protocol(String value) {
            this.value = value;
        }

        /** The configuration string for this protocol. */
        public String value() {
            return value;
        }

        /** Parses a configuration string; unknown values are rejected. */
        public static Protocol fromValue(String value) {
            for (Protocol p : values()) {
                if (p.value.equals(value)) {
                    return p;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown protocol: \"" + value + "\". Expected http/json or http/protobuf.");
        }
    }

    private static final String SCOPE_NAME = "software.amazon.lambda.durable.insight";

    private final URI endpoint;
    private final Map<String, String> headers;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final HttpClient httpClient;

    private OTelExporter(Builder b) {
        if (b.protocol == Protocol.HTTP_PROTOBUF) {
            throw new IllegalArgumentException("OTelExporter: http/protobuf is not yet supported. Use http/json.");
        }
        this.endpoint = URI.create(requireNonNull(b.endpoint, "endpoint"));
        this.headers = b.headers != null ? Map.copyOf(b.headers) : Map.of();
        this.operationsFormat = b.operationsFormat != null ? b.operationsFormat : OperationsFormat.ARRAY;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 1_000_000;
        this.httpClient = b.httpClient != null ? b.httpClient : HttpClient.newHttpClient();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Integer maxRecordSizeBytes() {
        return maxRecordSizeBytes;
    }

    /** The full OTLP request, so size limits cover the record content and the OTLP envelope together. */
    @Override
    public Object render(WorkflowInsightRecord record) {
        return buildPayload(record);
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        String body = Json.stringify(buildPayload(record));
        HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .setHeader("Content-Type", "application/json");
        headers.forEach(rb::setHeader);
        HttpResponse<Void> response;
        try {
            response = httpClient.send(rb.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException("OTelExporter: request to OTLP endpoint failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OTelExporter: interrupted while sending to OTLP endpoint", e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("OTelExporter: OTLP endpoint returned " + status);
        }
    }

    private Map<String, Object> buildPayload(WorkflowInsightRecord record) {
        Map<String, Object> wire = record.toWireMap();
        String functionName = record.functionName();
        Map<String, Object> resource = Map.of(
                "attributes",
                List.of(
                        kv("service.name", functionName),
                        kv("cloud.region", (String) wire.get("region")),
                        kv("cloud.account.id", (String) wire.get("accountId")),
                        kv("faas.name", functionName),
                        kv("faas.version", (String) wire.get("functionQualifier"))));
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("name", SCOPE_NAME);
        scope.put("version", wire.get("schemaVersion"));

        Long durationMs = (Long) wire.get("durationMs");
        String executionName = record.executionName();
        Map<String, Object> logRecord = new LinkedHashMap<>();
        logRecord.put("timeUnixNano", toNano((String) wire.get("emittedAt")));
        logRecord.put("severityNumber", severityFor(record.status()));
        logRecord.put("severityText", record.status());
        logRecord.put("body", Map.of("stringValue", Json.stringify(operationsFormat.apply(record))));
        logRecord.put(
                "attributes",
                List.of(
                        kv("workflow.execution_arn", record.executionArn()),
                        kv("workflow.execution_name", executionName != null ? executionName : ""),
                        kv("workflow.status", record.status()),
                        kv("workflow.duration_ms", durationMs != null ? durationMs : 0L)));

        Map<String, Object> scopeLogs = new LinkedHashMap<>();
        scopeLogs.put("scope", scope);
        scopeLogs.put("logRecords", List.of(logRecord));
        Map<String, Object> resourceLogs = new LinkedHashMap<>();
        resourceLogs.put("resource", resource);
        resourceLogs.put("scopeLogs", List.of(scopeLogs));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceLogs", List.of(resourceLogs));
        return payload;
    }

    /** String attribute; an absent value renders as an empty {@code value} object. */
    private static Map<String, Object> kv(String key, String value) {
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("key", key);
        attr.put("value", value != null ? Map.of("stringValue", value) : Map.of());
        return attr;
    }

    /** Integer attribute; OTLP JSON carries 64-bit integers as strings. */
    private static Map<String, Object> kv(String key, long value) {
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("key", key);
        attr.put("value", Map.of("intValue", Long.toString(value)));
        return attr;
    }

    private static String toNano(String isoTimestamp) {
        return Long.toString(Instant.parse(isoTimestamp).toEpochMilli() * 1_000_000L);
    }

    private static int severityFor(String status) {
        if ("FAILED".equals(status)) {
            return 17; // ERROR
        }
        if ("RUNNING".equals(status) || "SUCCEEDED".equals(status)) {
            return 9; // INFO
        }
        return 0; // UNSPECIFIED
    }

    /** Builder for {@link OTelExporter}. */
    public static final class Builder {
        private String endpoint;
        private Map<String, String> headers;
        private Protocol protocol;
        private OperationsFormat operationsFormat;
        private Integer maxRecordSizeBytes;
        private HttpClient httpClient;

        /** OTLP logs endpoint, for example {@code http://localhost:4318/v1/logs}. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /** Default {@code HTTP_JSON}; {@code HTTP_PROTOBUF} is rejected at build time. */
        public Builder protocol(Protocol protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder operationsFormat(OperationsFormat operationsFormat) {
            this.operationsFormat = operationsFormat;
            return this;
        }

        public Builder maxRecordSizeBytes(Integer maxRecordSizeBytes) {
            this.maxRecordSizeBytes = maxRecordSizeBytes;
            return this;
        }

        /** Test seam: inject an HTTP client. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public OTelExporter build() {
            return new OTelExporter(this);
        }
    }
}
