// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Instrumented test caller: the trace header traverses the real signed Invoke request. */
public final class OtelInvoke {
    private static final JacksonSerDes JSON = new JacksonSerDes();

    private OtelInvoke() {}

    public record Input(String function, String executionName, String payload, String traceHeader) {}

    public static void main(String[] args) throws IOException {
        var input = JSON.deserialize(
                new String(System.in.readAllBytes(), StandardCharsets.UTF_8), TypeToken.get(Input.class));
        try (var client = LambdaClient.builder()
                .region(Region.of(System.getenv("AWS_REGION")))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .retryPolicy(RetryPolicy.none()))
                .build()) {
            var result = client.invoke(request(input));
            System.out.println(JSON.serialize(Map.of(
                    "headers",
                    Map.of("DurableExecutionArn", result.durableExecutionArn(), "StatusCode", result.statusCode()),
                    "body",
                    "")));
        }
    }

    static InvokeRequest request(Input input) {
        if (input.traceHeader() == null
                || !input.traceHeader().matches("Root=1-[0-9a-f]{8}-[0-9a-f]{24};Parent=[0-9a-f]{16};Sampled=[01]")) {
            throw new IllegalArgumentException("A valid test caller trace header is required");
        }
        return InvokeRequest.builder()
                .functionName(input.function())
                .durableExecutionName(input.executionName())
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String(input.payload()))
                .overrideConfiguration(config -> config.putHeader("X-Amzn-Trace-Id", input.traceHeader()))
                .build();
    }
}
