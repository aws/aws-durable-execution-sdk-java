// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon OpenSearch Service with the index API, using the execution ARN as the
 * document id so later exports overwrite the same document. Authenticates with SigV4 (default) or HTTP basic auth.
 * Requires {@code es:ESHttpPut} on the index for SigV4.
 */
@Experimental
public final class OpenSearchExporter implements InsightExporter {

    /** Authentication method. */
    @Experimental
    public enum Auth {
        /** IAM request signing for Amazon OpenSearch Service (default). */
        SIGV4("sigv4"),
        /** Username and password. */
        BASIC("basic");

        private final String value;

        Auth(String value) {
            this.value = value;
        }

        /** The configuration string for this method. */
        public String value() {
            return value;
        }

        /** Parses a configuration string; unknown values are rejected. */
        public static Auth fromValue(String value) {
            for (Auth a : values()) {
                if (a.value.equals(value)) {
                    return a;
                }
            }
            throw new IllegalArgumentException("Unknown auth: \"" + value + "\". Expected sigv4 or basic.");
        }
    }

    /** Headers the JDK HTTP client manages itself and refuses to accept from callers. */
    private static final Set<String> CLIENT_MANAGED_HEADERS = Set.of("host", "content-length");

    private final String endpoint;
    private final String indexName;
    private final String region;
    private final Auth auth;
    private final String username;
    private final String password;
    private final Integer maxRecordSizeBytes;
    private final HttpClient httpClient;
    private final LazyClient<Signer> signer;

    private OpenSearchExporter(Builder b) {
        this.endpoint = requireNonNull(b.endpoint, "endpoint").replaceAll("/$", "");
        this.indexName = b.indexName != null ? b.indexName : "workflow-insight";
        this.region = b.region;
        this.auth = b.auth != null ? b.auth : Auth.SIGV4;
        this.username = b.username;
        this.password = b.password;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 10_000_000;
        this.httpClient = b.httpClient != null ? b.httpClient : HttpClient.newHttpClient();
        if (this.auth == Auth.SIGV4) {
            requireNonNull(region, "region is required for sigv4 auth");
        } else {
            requireNonNull(username, "username is required for basic auth");
            requireNonNull(password, "password is required for basic auth");
        }
        AwsCredentialsProvider credentials = b.credentialsProvider;
        this.signer = new LazyClient<>(null, "http-auth-aws", () -> new Signer(credentials));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Integer maxRecordSizeBytes() {
        return maxRecordSizeBytes;
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        URI url = URI.create(endpoint + "/" + indexName + "/_doc/" + encodeComponent(record.executionArn()));
        String body = Json.stringify(record.toWireMap());
        HttpRequest.Builder rb = HttpRequest.newBuilder(url)
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .setHeader("Content-Type", "application/json");
        if (auth == Auth.BASIC) {
            String token =
                    Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            rb.setHeader("Authorization", "Basic " + token);
        } else {
            // Use the signed header set as-is: it already carries content-type, the date, and the authorization.
            signer.get().sign(url, body).forEach((name, values) -> {
                if (!CLIENT_MANAGED_HEADERS.contains(name.toLowerCase())) {
                    rb.setHeader(name, String.join(",", values));
                }
            });
        }
        HttpResponse<String> response = send(rb.build());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String detail = response.body() != null ? response.body() : "";
            throw new IllegalStateException("OpenSearch index failed: " + status
                    + (detail.isEmpty() ? "" : " — " + detail.substring(0, Math.min(500, detail.length()))));
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("OpenSearch index request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenSearch index request interrupted", e);
        }
    }

    /** Percent-encodes a path segment, keeping the unreserved characters {@code A-Z a-z 0-9 - _ . ! ~ * ' ( )}. */
    static String encodeComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }

    /** SigV4 signing for the {@code es} service; created on first use so basic-auth users never load the signer. */
    private final class Signer {
        private final AwsV4HttpSigner v4 = AwsV4HttpSigner.create();
        private final AwsCredentialsProvider credentials;

        Signer(AwsCredentialsProvider credentials) {
            this.credentials = credentials != null ? credentials : DefaultCredentialsProvider.create();
        }

        Map<String, List<String>> sign(URI url, String body) {
            String host = url.getPort() == -1 ? url.getHost() : url.getHost() + ":" + url.getPort();
            SdkHttpRequest request = SdkHttpRequest.builder()
                    .method(SdkHttpMethod.PUT)
                    .uri(url)
                    .putHeader("host", host)
                    .putHeader("content-type", "application/json")
                    .build();
            SignedRequest signed = v4.sign(r -> r.identity(credentials.resolveCredentials())
                    .request(request)
                    .payload(ContentStreamProvider.fromUtf8String(body))
                    .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "es")
                    .putProperty(AwsV4HttpSigner.REGION_NAME, region));
            return signed.request().headers();
        }
    }

    /** Builder for {@link OpenSearchExporter}. */
    public static final class Builder {
        private String endpoint;
        private String indexName;
        private String region;
        private Auth auth;
        private String username;
        private String password;
        private Integer maxRecordSizeBytes;
        private HttpClient httpClient;
        private AwsCredentialsProvider credentialsProvider;

        /** Domain endpoint, for example {@code https://my-domain.us-east-1.es.amazonaws.com}. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Index name. Default {@code workflow-insight}. */
        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        /** Signing region; required for {@code SIGV4}. */
        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /** Default {@code SIGV4}. */
        public Builder auth(Auth auth) {
            this.auth = auth;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
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

        /** Credentials for SigV4 signing; defaults to the SDK default provider chain. */
        public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
            this.credentialsProvider = credentialsProvider;
            return this;
        }

        public OpenSearchExporter build() {
            return new OpenSearchExporter(this);
        }
    }
}
