// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

class OtelInvokeTest {
    @Test
    void realAwsSdkSendsTheTraceHeaderWithoutChangingPayload() throws Exception {
        var header = "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=0";
        var observed = new AtomicReference<String>();
        var payload = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            observed.set(exchange.getRequestHeaders().getFirst("X-Amzn-Trace-Id"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("X-Amz-Durable-Execution-Arn", "execution");
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try (var client = LambdaClient.builder()
                .endpointOverride(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.US_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(5)))
                .build()) {
            var input = new OtelInvoke.Input("function:qualified", "stable-name", "{\"marker\":\"input\"}", header);
            var response = client.invoke(OtelInvoke.request(input));
            assertEquals(202, response.statusCode());
            assertEquals(header, observed.get());
            assertEquals(input.payload(), payload.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidHttpCarrierIsRejectedBeforeInvoke() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OtelInvoke.request(new OtelInvoke.Input("function:1", "name", "{}", "invalid\r\nheader")));
    }
}
