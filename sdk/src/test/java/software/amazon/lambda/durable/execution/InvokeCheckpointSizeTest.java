// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeOptions;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionRequest;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.client.LambdaDurableFunctionsClient;

/** Measures the real generated protocol body; only HTTP exchange is replaced. */
class InvokeCheckpointSizeTest {
    private static final int BUDGET = 750 * 1024;
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:parent/durable-execution/test/id";
    private static final String HEADER = "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=1";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void traceOptionsSplitAnOtherwiseFittingBatchWithoutLosingUpdates(boolean escaped) throws Exception {
        var header = escaped ? HEADER + ";extension=" + "\\\"\n\u0001\u00e9\ud83d\ude80".repeat(128) : HEADER;
        try (var wire = new Wire()) {
            var empty = List.of(update("a", "", null), update("b", "", null));
            var overhead = wire.body(empty).length;
            var headerBytes = wire.body(List.of(update("a", "", header), update("b", "", header))).length - overhead;
            var payload = "x".repeat((BUDGET - overhead - headerBytes + 40) / 2);
            var plain = List.of(update("a", payload, null), update("b", payload, null));
            assertTrue(wire.body(plain).length <= BUDGET, "control batch fits before adding trace headers");
            var updates = List.of(update("a", payload, header), update("b", payload, header));
            assertTrue(wire.body(updates).length > BUDGET, "headers cross the real JSON byte boundary");
            wire.requests.clear();
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(wire.client)
                    .withCheckpointDelay(Duration.ofHours(1))
                    .build();
            var manager = new CheckpointManager(config, ARN, "token", operations -> {});
            var first = manager.checkpoint(updates.get(0));
            var second = manager.checkpoint(updates.get(1));
            manager.shutdown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(2, wire.requests.size(), "the added encoded options must force two batches");
            var mapper = new ObjectMapper();
            for (var index = 0; index < wire.requests.size(); index++) {
                var bytes = wire.requests.get(index);
                assertTrue(bytes.length <= BUDGET, "each actual wire request stays within the batching budget");
                var items = mapper.readTree(bytes).get("Updates");
                assertEquals(1, items.size());
                var item = items.get(0);
                assertEquals(updates.get(index).id(), item.get("Id").asText());
                assertEquals(updates.get(index).payload(), item.get("Payload").asText());
                assertEquals(
                        header,
                        item.get("ChainedInvokeOptions").get("XAmznTraceId").asText());
            }
        }
    }

    private static OperationUpdate update(String id, String payload, String header) {
        var options = ChainedInvokeOptions.builder().functionName("target:live").tenantId("tenant");
        if (header != null) options.xAmznTraceId(header);
        return OperationUpdate.builder()
                .id(id)
                .type(OperationType.CHAINED_INVOKE)
                .action(OperationAction.START)
                .payload("\"" + payload + "\"")
                .chainedInvokeOptions(options.build())
                .build();
    }

    private static final class Wire implements AutoCloseable {
        final List<byte[]> requests = new CopyOnWriteArrayList<>();
        final LambdaClient lambda;
        final LambdaDurableFunctionsClient client;

        Wire() throws IOException {
            var http = mock(SdkHttpClient.class);
            when(http.prepareRequest(any())).thenAnswer(invocation -> {
                HttpExecuteRequest request = invocation.getArgument(0);
                try (var body = request.contentStreamProvider().orElseThrow().newStream()) {
                    requests.add(body.readAllBytes());
                }
                return new ExecutableHttpRequest() {
                    @Override
                    public HttpExecuteResponse call() {
                        return HttpExecuteResponse.builder()
                                .response(SdkHttpResponse.builder()
                                        .statusCode(200)
                                        .putHeader("Content-Type", "application/json")
                                        .build())
                                .responseBody(AbortableInputStream.create(new ByteArrayInputStream(
                                        "{\"CheckpointToken\":\"token\"}".getBytes(StandardCharsets.UTF_8))))
                                .build();
                    }

                    @Override
                    public void abort() {}
                };
            });
            lambda = LambdaClient.builder()
                    .region(Region.US_EAST_1)
                    .httpClient(http)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .overrideConfiguration(config -> config.addExecutionInterceptor(new ExecutionInterceptor() {
                        @Override
                        public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes attributes) {
                            return ((CheckpointDurableExecutionRequest) context.request())
                                    .toBuilder()
                                            .clientToken("batch-size-fixture")
                                            .build();
                        }
                    }))
                    .build();
            client = new LambdaDurableFunctionsClient(lambda);
        }

        byte[] body(List<OperationUpdate> updates) {
            client.checkpoint(ARN, "token", updates);
            return requests.get(requests.size() - 1);
        }

        @Override
        public void close() {
            lambda.close();
        }
    }
}
