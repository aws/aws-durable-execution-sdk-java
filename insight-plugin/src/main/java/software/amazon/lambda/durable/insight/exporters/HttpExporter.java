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
import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to an HTTP endpoint as a JSON body, one request per record. A non-2xx response fails
 * the export. No truncation limit by default; set one if the endpoint caps request size.
 */
@Experimental
public final class HttpExporter implements InsightExporter {

    /** Request method. */
    @Experimental
    public enum Method {
        POST,
        PUT;

        /** Parses a configuration string; unknown values are rejected. */
        public static Method fromValue(String value) {
            for (Method m : values()) {
                if (m.name().equals(value)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("Unknown method: \"" + value + "\". Expected POST or PUT.");
        }
    }

    private final URI url;
    private final Method method;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final HttpClient httpClient;

    private HttpExporter(Builder b) {
        this.url = URI.create(requireNonNull(b.url, "url"));
        this.method = b.method != null ? b.method : Method.POST;
        this.headers = b.headers != null ? Map.copyOf(b.headers) : Map.of();
        this.timeout = Duration.ofMillis(b.timeoutMs != null ? b.timeoutMs : 10_000L);
        this.operationsFormat = b.operationsFormat != null ? b.operationsFormat : OperationsFormat.ARRAY;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes;
        this.httpClient = b.httpClient != null
                ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Integer maxRecordSizeBytes() {
        return maxRecordSizeBytes;
    }

    @Override
    public Object render(WorkflowInsightRecord record) {
        return operationsFormat.apply(record);
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        String body = Json.stringify(render(record));
        HttpRequest.Builder rb = HttpRequest.newBuilder(url)
                .method(method.name(), HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(timeout)
                .setHeader("Content-Type", "application/json");
        headers.forEach(rb::setHeader);
        HttpResponse<Void> response;
        try {
            response = httpClient.send(rb.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException("HttpExporter: request to " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HttpExporter: interrupted while sending to " + url, e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HttpExporter: endpoint returned " + status);
        }
    }

    /** Builder for {@link HttpExporter}. */
    public static final class Builder {
        private String url;
        private Map<String, String> headers;
        private Method method;
        private Long timeoutMs;
        private OperationsFormat operationsFormat;
        private Integer maxRecordSizeBytes;
        private HttpClient httpClient;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /** Extra request headers, such as authorization tokens; may override {@code Content-Type}. */
        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /** Default {@code POST}. */
        public Builder method(Method method) {
            this.method = method;
            return this;
        }

        /** Request timeout in milliseconds. Default 10000. */
        public Builder timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder operationsFormat(OperationsFormat operationsFormat) {
            this.operationsFormat = operationsFormat;
            return this;
        }

        /** No default: a generic endpoint has no known limit. */
        public Builder maxRecordSizeBytes(Integer maxRecordSizeBytes) {
            this.maxRecordSizeBytes = maxRecordSizeBytes;
            return this;
        }

        /** Test seam: inject an HTTP client. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public HttpExporter build() {
            return new HttpExporter(this);
        }
    }
}
