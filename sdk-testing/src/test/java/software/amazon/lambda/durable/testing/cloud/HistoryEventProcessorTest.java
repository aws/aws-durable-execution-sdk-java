// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeStartedDetails;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeSucceededDetails;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.EventResult;
import software.amazon.awssdk.services.lambda.model.EventType;
import software.amazon.awssdk.services.lambda.model.ExecutionStartedDetails;
import software.amazon.awssdk.services.lambda.model.ExecutionSucceededDetails;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.RetryDetails;
import software.amazon.awssdk.services.lambda.model.StepStartedDetails;
import software.amazon.awssdk.services.lambda.model.StepSucceededDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesRunner;

class HistoryEventProcessorTest {
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST"
            + "/durable-execution/execution-id/invocation-id";

    @Test
    void deserializesCloudResultsWithDurablePayloadContext() {
        var observedContexts = new ArrayList<SerDesContext>();
        var serDes = recordingStringSerDes(observedContexts);
        var startedAt = Instant.parse("2026-08-24T00:00:00Z");
        var events = List.of(
                Event.builder()
                        .id("invocation-id")
                        .name("execution")
                        .eventType(EventType.EXECUTION_STARTED)
                        .eventTimestamp(startedAt)
                        .executionStartedDetails(
                                ExecutionStartedDetails.builder().build())
                        .build(),
                Event.builder()
                        .id("step-id")
                        .name("step")
                        .subType("Step")
                        .eventType(EventType.STEP_STARTED)
                        .eventTimestamp(startedAt.plusSeconds(1))
                        .stepStartedDetails(StepStartedDetails.builder().build())
                        .build(),
                Event.builder()
                        .id("step-id")
                        .name("step")
                        .subType("Step")
                        .eventType(EventType.STEP_SUCCEEDED)
                        .eventTimestamp(startedAt.plusSeconds(3))
                        .stepSucceededDetails(StepSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload("step-result")
                                        .build())
                                .retryDetails(
                                        RetryDetails.builder().currentAttempt(2).build())
                                .build())
                        .build(),
                Event.builder()
                        .id("invoke-id")
                        .name("invoke")
                        .eventType(EventType.CHAINED_INVOKE_STARTED)
                        .eventTimestamp(startedAt.plusSeconds(4))
                        .chainedInvokeStartedDetails(ChainedInvokeStartedDetails.builder()
                                .functionName("target")
                                .build())
                        .build(),
                Event.builder()
                        .id("invoke-id")
                        .name("invoke")
                        .eventType(EventType.CHAINED_INVOKE_SUCCEEDED)
                        .eventTimestamp(startedAt.plusSeconds(5))
                        .chainedInvokeSucceededDetails(ChainedInvokeSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload("invoke-result")
                                        .build())
                                .build())
                        .build(),
                Event.builder()
                        .id("invocation-id")
                        .name("execution")
                        .eventType(EventType.EXECUTION_SUCCEEDED)
                        .eventTimestamp(startedAt.plusSeconds(6))
                        .executionSucceededDetails(ExecutionSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload("execution-result")
                                        .build())
                                .build())
                        .build());

        var result = new HistoryEventProcessor()
                .processEvents(events, TypeToken.get(String.class), serDes, new SerDesRunner(null), EXECUTION_ARN);

        assertEquals("execution-result", result.getResult());
        assertEquals("step-result", result.getOperation("step").getStepResult(String.class));
        assertEquals(Duration.ofSeconds(2), result.getOperation("step").getDuration());
        assertEquals(OperationStatus.SUCCEEDED, result.getOperation("invoke").getStatus());
        assertEquals(
                "invoke-result",
                result.getOperation("invoke").getChainedInvokeDetails().result());
        assertEquals(
                List.of("invocation-id/output", "step-id/result"),
                observedContexts.stream().map(SerDesContext::entityId).toList());
    }

    private static SerDes recordingStringSerDes(List<SerDesContext> observedContexts) {
        return new SerDes() {
            @Override
            public String serialize(Object value) {
                return value.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                observedContexts.add(SerDesContext.getCurrentContext());
                return (T) data;
            }
        };
    }
}
