// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.EventResult;
import software.amazon.awssdk.services.lambda.model.EventType;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionHistoryRequest;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionHistoryResponse;
import software.amazon.awssdk.services.lambda.model.RetryDetails;
import software.amazon.awssdk.services.lambda.model.StepStartedDetails;
import software.amazon.awssdk.services.lambda.model.StepSucceededDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.SerDes;

class AsyncExecutionTest {
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST"
            + "/durable-execution/execution-id/invocation-id";

    @Test
    void scopesDeserializationCacheToOneHistorySnapshot() {
        var lambdaClient = mock(LambdaClient.class);
        when(lambdaClient.getDurableExecutionHistory(any(GetDurableExecutionHistoryRequest.class)))
                .thenReturn(GetDurableExecutionHistoryResponse.builder()
                        .events(stepEvents())
                        .build());
        var deserializations = new AtomicInteger();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return value.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                deserializations.incrementAndGet();
                return (T) data;
            }
        };
        var execution = new AsyncExecution<>(
                EXECUTION_ARN, lambdaClient, TypeToken.get(String.class), serDes, Duration.ZERO, Duration.ofSeconds(1));
        var snapshots = new AtomicInteger();

        execution.pollUntil(current -> {
            assertEquals("step-result", current.getOperation("step").getStepResult(String.class));
            assertEquals("step-result", current.getOperation("step").getStepResult(String.class));
            return snapshots.incrementAndGet() == 2;
        });

        assertEquals(2, deserializations.get());
    }

    private static List<Event> stepEvents() {
        var startedAt = Instant.parse("2026-08-25T00:00:00Z");
        return List.of(
                Event.builder()
                        .id("step-id")
                        .name("step")
                        .subType("Step")
                        .eventType(EventType.STEP_STARTED)
                        .eventTimestamp(startedAt)
                        .stepStartedDetails(StepStartedDetails.builder().build())
                        .build(),
                Event.builder()
                        .id("step-id")
                        .name("step")
                        .subType("Step")
                        .eventType(EventType.STEP_SUCCEEDED)
                        .eventTimestamp(startedAt.plusSeconds(1))
                        .stepSucceededDetails(StepSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload("step-result")
                                        .build())
                                .retryDetails(
                                        RetryDetails.builder().currentAttempt(1).build())
                                .build())
                        .build());
    }
}
