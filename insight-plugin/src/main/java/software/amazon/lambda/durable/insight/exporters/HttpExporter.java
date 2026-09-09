// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.OperationSummary;
import software.amazon.lambda.durable.insight.OperationsIndex;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to any HTTP(S) endpoint via {@code POST} (or {@code PUT}). Each record is sent as a
 * JSON body with {@code Content-Type: application/json}; the endpoint must return a 2xx status or the export throws.
 * Mirrors the JS {@code HttpExporter} contract (URL, method, custom headers, request timeout, operations format, and
 * optional size cap). Uses the JDK's own HTTP client, so it needs no extra dependency.
 */
@Experimental
public final class HttpExporter implements InsightExporter {

    /** HTTP method used to deliver the record. */
    @Experimental
    public enum Method {
        POST,
        PUT
    }

    /**
     * How the record's operations are shaped in the posted body: the canonical {@code operations} array, the name-keyed
     * {@code operationsByName} map, or both. Ports the JS {@code OperationsFormat}.
     */
    @Experimental
    public enum OperationsFormat {
        ARRAY,
        BY_NAME,
        BOTH
    }

    private final String url;
    private final Method method;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final HttpSender sender;

    private HttpExporter(Builder b) {
        this.url = b.url;
        this.method = b.method != null ? b.method : Method.POST;
        // Defensive copy so a caller mutating their builder map after build() cannot change this exporter's headers.
        this.headers = b.headers != null ? new LinkedHashMap<>(b.headers) : new LinkedHashMap<>();
        this.timeout = b.timeoutMs != null ? Duration.ofMillis(b.timeoutMs) : Duration.ofMillis(10_000);
        this.operationsFormat = b.operationsFormat != null ? b.operationsFormat : OperationsFormat.ARRAY;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes;
        this.sender = b.sender != null ? b.sender : new JdkHttpSender();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Integer maxRecordSizeBytes() {
        // No default: a generic HTTP endpoint has no known size limit (matches JS). Truncation is off unless set.
        return maxRecordSizeBytes;
    }

    @Override
    public Object render(WorkflowInsightRecord record) {
        switch (operationsFormat) {
            case BY_NAME:
                return record.toByNameWireMap();
            case BOTH:
                return toBothWireMap(record);
            case ARRAY:
            default:
                return record.toWireMap();
        }
    }

    /**
     * The {@code both} shape: the canonical array wire map with an added {@code operationsByName} map. Composed here
     * from the record's public renderings so the SDK core needs no new method. The {@code operationsByName} entry is
     * placed before the trailing truncation markers to keep a stable, readable field order.
     */
    private Map<String, Object> toBothWireMap(WorkflowInsightRecord record) {
        Map<String, Object> data = new LinkedHashMap<>(record.toWireMap());
        Map<String, Object> byName = new LinkedHashMap<>();
        for (Map.Entry<String, OperationSummary> e :
                OperationsIndex.buildOperationsByName(record.operations()).entrySet()) {
            byName.put(e.getKey(), e.getValue().toWireMap());
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            // Insert operationsByName immediately after the operations array, before truncation markers.
            if ("truncated".equals(e.getKey()) && !merged.containsKey("operationsByName")) {
                merged.put("operationsByName", byName);
            }
            merged.put(e.getKey(), e.getValue());
        }
        merged.putIfAbsent("operationsByName", byName);
        return merged;
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        String body = Json.stringify(render(record));
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Content-Type", "application/json");
        requestHeaders.putAll(headers);
        HttpSender.Response response = sender.send(url, method.name(), requestHeaders, body, timeout);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(
                    "HttpExporter: endpoint returned " + status + " " + response.reasonPhrase());
        }
    }

    /** Builder for {@link HttpExporter}. */
    public static final class Builder {
        private String url;
        private Method method;
        private Map<String, String> headers;
        private Integer timeoutMs;
        private OperationsFormat operationsFormat;
        private Integer maxRecordSizeBytes;
        private HttpSender sender;

        /** Endpoint to POST/PUT records to (required). Must be an absolute {@code http} or {@code https} URL. */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /** HTTP method. Default {@link Method#POST}. Use {@link Method#PUT} for endpoints that upsert by URL path. */
        public Builder method(Method method) {
            this.method = method;
            return this;
        }

        /** Additional request headers (for example an {@code Authorization} token or API key). Copied defensively. */
        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /** Adds a single request header; convenient for one auth header without building a map. */
        public Builder addHeader(String name, String value) {
            if (this.headers == null) {
                this.headers = new LinkedHashMap<>();
            }
            this.headers.put(name, value);
            return this;
        }

        /** Request timeout in milliseconds. Default 10000 (10s). Must be positive. */
        public Builder timeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /** How operations are rendered in the posted body. Default {@link OperationsFormat#ARRAY}. */
        public Builder operationsFormat(OperationsFormat operationsFormat) {
            this.operationsFormat = operationsFormat;
            return this;
        }

        /** Max serialized record size before truncation. No default; set it if your endpoint caps request size. */
        public Builder maxRecordSizeBytes(Integer maxRecordSizeBytes) {
            this.maxRecordSizeBytes = maxRecordSizeBytes;
            return this;
        }

        /** Test seam: inject an {@link HttpSender} instead of the default JDK HTTP client. */
        public Builder sender(HttpSender sender) {
            this.sender = sender;
            return this;
        }

        public HttpExporter build() {
            validate();
            return new HttpExporter(this);
        }

        private void validate() {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("HttpExporter requires a url");
            }
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("HttpExporter url is not a valid URI: " + url, e);
            }
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) {
                throw new IllegalArgumentException("HttpExporter url must be absolute with a host: " + url);
            }
            String lower = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lower) && !"https".equals(lower)) {
                throw new IllegalArgumentException("HttpExporter url scheme must be http or https: " + url);
            }
            if (timeoutMs != null && timeoutMs <= 0) {
                throw new IllegalArgumentException("HttpExporter timeoutMs must be positive: " + timeoutMs);
            }
            if (maxRecordSizeBytes != null && maxRecordSizeBytes <= 0) {
                throw new IllegalArgumentException(
                        "HttpExporter maxRecordSizeBytes must be positive: " + maxRecordSizeBytes);
            }
        }
    }
}
