// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

/** Uses the real generated Lambda protocol marshaller; only the HTTP exchange is replaced. */
class InvokePropagationSerializationTest {
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:parent/durable-execution/test/id";
    private static final String PAYLOAD = "{\"traceparent\":\"customer-owned\",\"nested\":{\"value\":42}}";

    @ParameterizedTest
    @ValueSource(strings = {"1", "0", "absent"})
    void checkpointSerializationPreservesFlatPerOperationHeadersThroughModelCopy(String sampled) throws Exception {
        var updates = List.of(update("a", sampled), update("b", sampled));
        // Exercise generated immutable-model copy paths before the real client serializer.
        var copied = updates.stream()
                .map(item -> item.toBuilder()
                        .chainedInvokeOptions(
                                item.chainedInvokeOptions().toBuilder().build())
                        .build())
                .toList();
        var requests = new ArrayList<JsonNode>();
        var mapper = new ObjectMapper();
        var http = mock(SdkHttpClient.class);
        when(http.prepareRequest(any())).thenAnswer(call -> {
            HttpExecuteRequest request = call.getArgument(0);
            try (var body = request.contentStreamProvider().orElseThrow().newStream()) {
                requests.add(mapper.readTree(body));
            }
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    var body = new ByteArrayInputStream(
                            "{\"CheckpointToken\":\"next-token\"}".getBytes(StandardCharsets.UTF_8));
                    return HttpExecuteResponse.builder()
                            .response(SdkHttpResponse.builder()
                                    .statusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .build())
                            .responseBody(AbortableInputStream.create(body))
                            .build();
                }

                @Override
                public void abort() {}
            };
        });
        try (var lambda = LambdaClient.builder()
                .region(Region.US_EAST_1)
                .httpClient(http)
                .overrideConfiguration(configuration ->
                        configuration.addExecutionInterceptor(new ExecutionInterceptor() {
                            @Override
                            public SdkRequest modifyRequest(
                                    Context.ModifyRequest context, ExecutionAttributes attributes) {
                                // Stabilize only this fixture's automatic idempotency field, preserving the full wire
                                // comparison.
                                return ((CheckpointDurableExecutionRequest) context.request())
                                        .toBuilder()
                                                .clientToken("propagation-serialization-fixture")
                                                .build();
                            }
                        }))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                .build()) {
            var client = new LambdaDurableFunctionsClient(lambda);
            assertEquals("next-token", client.checkpoint(ARN, "token", updates).checkpointToken());
            assertEquals("next-token", client.checkpoint(ARN, "token", copied).checkpointToken());
        }
        assertEquals(2, requests.size());
        assertEquals(requests.get(0), requests.get(1), "Model copies must preserve the complete checkpoint payload");
        assertEquals(
                "propagation-serialization-fixture",
                requests.get(0).get("ClientToken").asText());
        var serialized = requests.get(0).get("Updates");
        assertEquals(2, serialized.size());
        for (var index = 0; index < updates.size(); index++) {
            var original = updates.get(index);
            var wire = serialized.get(index);
            assertEquals(original.id(), wire.get("Id").asText());
            assertEquals(original.parentId(), wire.get("ParentId").asText());
            assertEquals(original.name(), wire.get("Name").asText());
            assertEquals("CHAINED_INVOKE", wire.get("Type").asText());
            assertEquals("START", wire.get("Action").asText());
            assertEquals(PAYLOAD, wire.get("Payload").asText());
            var options = wire.get("ChainedInvokeOptions");
            assertEquals(
                    original.chainedInvokeOptions().functionName(),
                    options.get("FunctionName").asText());
            assertEquals(
                    original.chainedInvokeOptions().tenantId(),
                    options.get("TenantId").asText());
            assertFalse(options.has("PropagationMetadata"), "The model contract is flat, not the older nested sketch");
            if (sampled.equals("absent")) {
                assertFalse(options.has("XAmznTraceId"), "Absent contribution must omit the field, not send null");
            } else {
                assertEquals(
                        original.chainedInvokeOptions().xAmznTraceId(),
                        options.get("XAmznTraceId").asText());
            }
        }
        if (!sampled.equals("absent")) {
            assertNotEquals(
                    serialized.get(0).get("ChainedInvokeOptions").get("XAmznTraceId"),
                    serialized.get(1).get("ChainedInvokeOptions").get("XAmznTraceId"));
        }
    }

    private static OperationUpdate update(String suffix, String sampled) {
        var options = ChainedInvokeOptions.builder()
                .functionName("target-" + suffix + ":live")
                .tenantId("tenant-" + suffix);
        if (!sampled.equals("absent")) {
            options.xAmznTraceId(
                    "Root=1-6955b900-123456789012345678901234;Parent=" + suffix.repeat(16) + ";Sampled=" + sampled);
        }
        return OperationUpdate.builder()
                .id("invoke-" + suffix)
                .parentId("context-" + suffix)
                .name("call-" + suffix)
                .type(OperationType.CHAINED_INVOKE)
                .subType("ChainedInvoke")
                .action(OperationAction.START)
                .payload(PAYLOAD)
                .chainedInvokeOptions(options.build())
                .build();
    }
}
