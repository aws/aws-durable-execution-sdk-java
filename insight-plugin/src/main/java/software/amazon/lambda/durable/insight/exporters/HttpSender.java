// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Test/injection seam for {@link HttpExporter}'s HTTP transport. The default implementation ({@link JdkHttpSender}) is
 * backed by {@link java.net.http.HttpClient}; tests inject a stub or point the exporter at a local in-process server.
 * Mirrors the {@code client(...)} builder seam on the AWS SDK exporters, but avoids adding any dependency by staying on
 * the JDK's own HTTP client.
 */
@Experimental
public interface HttpSender {

    /**
     * Sends one request and returns the response status. Implementations MUST apply {@code timeout} to the request and
     * MUST throw on transport failure (I/O error, timeout, interruption) so the exporter surfaces it like the JS
     * reference (whose {@code fetch} rejects). {@code headers} already includes {@code Content-Type: application/json}.
     */
    Response send(String url, String method, Map<String, String> headers, String body, Duration timeout);

    /** The parts of an HTTP response the exporter needs to decide success/failure. */
    @Experimental
    final class Response {
        private final int statusCode;
        private final String reasonPhrase;

        public Response(int statusCode, String reasonPhrase) {
            this.statusCode = statusCode;
            this.reasonPhrase = reasonPhrase != null ? reasonPhrase : "";
        }

        public int statusCode() {
            return statusCode;
        }

        public String reasonPhrase() {
            return reasonPhrase;
        }
    }
}
