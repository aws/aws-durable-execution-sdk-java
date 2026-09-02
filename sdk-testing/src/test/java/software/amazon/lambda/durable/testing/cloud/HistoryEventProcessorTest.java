// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.EventResult;
import software.amazon.awssdk.services.lambda.model.EventType;
import software.amazon.awssdk.services.lambda.model.ExecutionStartedDetails;
import software.amazon.awssdk.services.lambda.model.ExecutionSucceededDetails;
import software.amazon.awssdk.services.lambda.model.RetryDetails;
import software.amazon.awssdk.services.lambda.model.StepStartedDetails;
import software.amazon.awssdk.services.lambda.model.StepSucceededDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.execution.PayloadCodec;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.offload.filesystem.FileSystemPayloadOffloader;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class HistoryEventProcessorTest {
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST"
            + "/durable-execution/execution-id/invocation-id";

    @TempDir
    Path payloadDirectory;

    @Test
    void resolvesOffloadedCloudExecutionAndStepResults() {
        var serDes = new JacksonSerDes();
        var offloader = FileSystemPayloadOffloader.builder(payloadDirectory).build();
        var writer = new PayloadCodec(null);
        var outputPayload = writer.serialize(
                "execution-result",
                serDes,
                offloader,
                PayloadOffloadContext.forExecution(
                        EXECUTION_ARN, "invocation-id", "execution-id", SerDesPayloadKind.OUTPUT));
        var stepPayload = writer.serialize(
                "step-result",
                serDes,
                offloader,
                PayloadOffloadContext.forOperation(
                        EXECUTION_ARN,
                        OperationIdentifier.of("step-id", "step", OperationSubType.STEP),
                        null,
                        SerDesPayloadKind.RESULT,
                        2));
        var startedAt = Instant.parse("2026-09-01T00:00:00Z");
        var events = List.of(
                Event.builder()
                        .id("invocation-id")
                        .name("execution-id")
                        .eventType(EventType.EXECUTION_STARTED)
                        .eventTimestamp(startedAt)
                        .executionStartedDetails(
                                ExecutionStartedDetails.builder().build())
                        .build(),
                Event.builder()
                        .id("step-id")
                        .name("step")
                        .subType(OperationSubType.STEP.getValue())
                        .eventType(EventType.STEP_STARTED)
                        .eventTimestamp(startedAt.plusSeconds(1))
                        .stepStartedDetails(StepStartedDetails.builder().build())
                        .build(),
                Event.builder()
                        .id("step-id")
                        .name("step")
                        .subType(OperationSubType.STEP.getValue())
                        .eventType(EventType.STEP_SUCCEEDED)
                        .eventTimestamp(startedAt.plusSeconds(2))
                        .stepSucceededDetails(StepSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload(stepPayload)
                                        .build())
                                .retryDetails(
                                        RetryDetails.builder().currentAttempt(2).build())
                                .build())
                        .build(),
                Event.builder()
                        .id("invocation-id")
                        .name("execution-id")
                        .eventType(EventType.EXECUTION_SUCCEEDED)
                        .eventTimestamp(startedAt.plusSeconds(3))
                        .executionSucceededDetails(ExecutionSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload(outputPayload)
                                        .build())
                                .build())
                        .build());

        var result = new HistoryEventProcessor()
                .processEvents(
                        events, TypeToken.get(String.class), serDes, new PayloadCodec(null), offloader, EXECUTION_ARN);

        assertEquals("execution-result", result.getResult());
        assertEquals("step-result", result.getOperation("step").getStepResult(String.class));
        assertEquals(Duration.ofSeconds(1), result.getOperation("step").getDuration());
    }

    @Test
    void resolvesOffloadedWaitForConditionStateUsingHistorySubtype() {
        var serDes = new JacksonSerDes();
        var offloader = FileSystemPayloadOffloader.builder(payloadDirectory).build();
        var statePayload = new PayloadCodec(null)
                .serialize(
                        42,
                        serDes,
                        offloader,
                        PayloadOffloadContext.forOperation(
                                EXECUTION_ARN,
                                OperationIdentifier.of(
                                        "condition-id", "condition", OperationSubType.WAIT_FOR_CONDITION),
                                "parent-id",
                                SerDesPayloadKind.STATE,
                                3));
        var startedAt = Instant.parse("2026-09-01T00:00:00Z");
        var events = List.of(
                Event.builder()
                        .id("condition-id")
                        .name("condition")
                        .parentId("parent-id")
                        .subType(OperationSubType.WAIT_FOR_CONDITION.getValue())
                        .eventType(EventType.STEP_STARTED)
                        .eventTimestamp(startedAt)
                        .stepStartedDetails(StepStartedDetails.builder().build())
                        .build(),
                Event.builder()
                        .id("condition-id")
                        .name("condition")
                        .parentId("parent-id")
                        .subType(OperationSubType.WAIT_FOR_CONDITION.getValue())
                        .eventType(EventType.STEP_SUCCEEDED)
                        .eventTimestamp(startedAt.plusSeconds(1))
                        .stepSucceededDetails(StepSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload(statePayload)
                                        .build())
                                .retryDetails(
                                        RetryDetails.builder().currentAttempt(3).build())
                                .build())
                        .build());

        var result = new HistoryEventProcessor()
                .processEvents(
                        events, TypeToken.get(Integer.class), serDes, new PayloadCodec(null), offloader, EXECUTION_ARN);

        assertEquals(42, result.getOperation("condition").getStepResult(Integer.class));
        assertEquals(
                OperationSubType.WAIT_FOR_CONDITION.getValue(),
                result.getOperation("condition").getSubtype());
    }

    @Test
    void threeArgumentOverloadResolvesInlineMarkerEnvelopesFromProducerContext() {
        var marker = "@aws-durable-payload:v2:{}";
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return (String) value;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return (T) data;
            }
        };
        var codec = new PayloadCodec(null);
        var outputPayload = codec.serialize(
                marker,
                serDes,
                PayloadOffloader.disabled(),
                PayloadOffloadContext.forExecution(
                        EXECUTION_ARN, "invocation-id", "execution-id", SerDesPayloadKind.OUTPUT));
        var stepPayload = codec.serialize(
                marker,
                serDes,
                PayloadOffloader.disabled(),
                PayloadOffloadContext.forOperation(
                        EXECUTION_ARN,
                        OperationIdentifier.of("step-id", "step", OperationSubType.STEP),
                        null,
                        SerDesPayloadKind.RESULT,
                        1));
        var startedAt = Instant.parse("2026-09-01T00:00:00Z");
        var events = List.of(
                Event.builder()
                        .id("step-id")
                        .name("step")
                        .subType(OperationSubType.STEP.getValue())
                        .eventType(EventType.STEP_SUCCEEDED)
                        .eventTimestamp(startedAt)
                        .stepSucceededDetails(StepSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload(stepPayload)
                                        .build())
                                .retryDetails(
                                        RetryDetails.builder().currentAttempt(1).build())
                                .build())
                        .build(),
                Event.builder()
                        .id("invocation-id")
                        .name("execution-id")
                        .eventType(EventType.EXECUTION_SUCCEEDED)
                        .eventTimestamp(startedAt.plusSeconds(1))
                        .executionSucceededDetails(ExecutionSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload(outputPayload)
                                        .build())
                                .build())
                        .build());

        var result = new HistoryEventProcessor().processEvents(events, TypeToken.get(String.class), serDes);

        assertEquals(marker, result.getResult());
        assertEquals(marker, result.getOperation("step").getStepResult(String.class));
    }

    @Test
    void recognizedReferenceEnvelopeWithoutOffloaderFailsOnResultAccess() {
        var serDes = new JacksonSerDes();
        var offloader = FileSystemPayloadOffloader.builder(payloadDirectory).build();
        var outputPayload = new PayloadCodec(null)
                .serialize(
                        "execution-result",
                        serDes,
                        offloader,
                        PayloadOffloadContext.forExecution(
                                EXECUTION_ARN, "invocation-id", "execution-id", SerDesPayloadKind.OUTPUT));
        var event = Event.builder()
                .id("invocation-id")
                .name("execution-id")
                .eventType(EventType.EXECUTION_SUCCEEDED)
                .eventTimestamp(Instant.parse("2026-09-01T00:00:00Z"))
                .executionSucceededDetails(ExecutionSucceededDetails.builder()
                        .result(EventResult.builder().payload(outputPayload).build())
                        .build())
                .build();

        var result = new HistoryEventProcessor()
                .processEvents(
                        List.of(event),
                        TypeToken.get(String.class),
                        serDes,
                        new PayloadCodec(null),
                        null,
                        EXECUTION_ARN);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertThrows(PayloadOffloadException.class, result::getResult);
    }
}
