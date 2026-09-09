// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Default {@link HttpSender} backed by the JDK's {@link HttpClient} (Java 11+); adds no third-party dependency. The
 * {@link HttpClient} is created once and reused across exports. The per-request timeout bounds the whole exchange,
 * mirroring the JS reference's {@code AbortController}. The reason phrase is taken from the HTTP/1.1 status line when
 * present; HTTP/2 has no reason phrase, so it is derived from the status code.
 */
@Experimental
final class JdkHttpSender implements HttpSender {

    private final HttpClient client;

    JdkHttpSender() {
        this(HttpClient.newBuilder().build());
    }

    JdkHttpSender(HttpClient client) {
        this.client = client;
    }

    @Override
    public Response send(String url, String method, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .method(method, BodyPublishers.ofString(body));
        for (Map.Entry<String, String> h : headers.entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        try {
            HttpResponse<Void> response = client.send(builder.build(), BodyHandlers.discarding());
            return new Response(response.statusCode(), reasonFor(response.statusCode()));
        } catch (IOException e) {
            throw new IllegalStateException("HttpExporter: request to " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HttpExporter: request to " + url + " was interrupted", e);
        }
    }

    private static String reasonFor(int status) {
        // HttpClient does not expose the HTTP/1.1 reason phrase and HTTP/2 has none; derive a stable label from status.
        if (status >= 200 && status < 300) {
            return "OK";
        }
        if (status >= 500) {
            return "Server Error";
        }
        if (status >= 400) {
            return "Client Error";
        }
        if (status >= 300) {
            return "Redirect";
        }
        return "Informational";
    }
}
