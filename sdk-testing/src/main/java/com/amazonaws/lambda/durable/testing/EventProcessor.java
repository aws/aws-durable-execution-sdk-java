// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import static software.amazon.awssdk.services.lambda.model.EventType.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.services.lambda.model.*;

/** Generates Event objects from OperationUpdate for local testing. */
class EventProcessor {
    private final AtomicInteger eventId = new AtomicInteger(1);

    Event processUpdate(OperationUpdate update, Operation operation) {
        var builder = Event.builder()
                .eventId(eventId.getAndIncrement())
                .eventTimestamp(Instant.now())
                .id(update.id())
                .name(update.name());

        return switch (update.type()) {
            case STEP -> buildStepEvent(builder, update, operation);
            case WAIT -> buildWaitEvent(builder, update, operation);
            case CHAINED_INVOKE -> buildInvokeEvent(builder, update, operation);
            case EXECUTION -> buildExecutionEvent(builder, update);
            case CALLBACK -> buildCallbackEvent(builder, update);
            case CONTEXT -> buildContextEvent(builder, update);
            default -> throw new IllegalArgumentException("Unsupported operation type: " + update.type());
        };
    }

    private Event buildStepEvent(Event.Builder builder, OperationUpdate update, Operation operation) {
        return switch (update.action()) {
            case START ->
                builder.eventType(STEP_STARTED)
                        .stepStartedDetails(StepStartedDetails.builder().build())
                        .build();
            case SUCCEED ->
                builder.eventType(STEP_SUCCEEDED)
                        .stepSucceededDetails(StepSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload(update.payload())
                                        .build())
                                .retryDetails(buildRetryDetails(operation))
                                .build())
                        .build();
            case FAIL ->
                builder.eventType(STEP_FAILED)
                        .stepFailedDetails(StepFailedDetails.builder()
                                .error(EventError.builder()
                                        .payload(update.error())
                                        .build())
                                .retryDetails(buildRetryDetails(operation))
                                .build())
                        .build();
            case RETRY ->
                builder.eventType(STEP_STARTED)
                        .stepStartedDetails(StepStartedDetails.builder().build())
                        .build();
            default -> throw new IllegalArgumentException("Unsupported step action: " + update.action());
        };
    }

    @SuppressWarnings("unused") // operation param kept for API consistency
    private Event buildWaitEvent(Event.Builder builder, OperationUpdate update, Operation operation) {
        return switch (update.action()) {
            case START -> {
                var waitSeconds =
                        update.waitOptions() != null ? update.waitOptions().waitSeconds() : 0;
                yield builder.eventType(WAIT_STARTED)
                        .waitStartedDetails(WaitStartedDetails.builder()
                                .duration(waitSeconds)
                                .scheduledEndTimestamp(Instant.now().plusSeconds(waitSeconds))
                                .build())
                        .build();
            }
            case SUCCEED ->
                builder.eventType(WAIT_SUCCEEDED)
                        .waitSucceededDetails(WaitSucceededDetails.builder().build())
                        .build();
            case CANCEL ->
                builder.eventType(WAIT_CANCELLED)
                        .waitCancelledDetails(WaitCancelledDetails.builder().build())
                        .build();
            default -> throw new IllegalArgumentException("Unsupported wait action: " + update.action());
        };
    }

    @SuppressWarnings("unused") // operation param kept for API consistency
    private Event buildInvokeEvent(Event.Builder builder, OperationUpdate update, Operation operation) {
        return switch (update.action()) {
            case START ->
                builder.eventType(EventType.CHAINED_INVOKE_STARTED)
                        .chainedInvokeStartedDetails(ChainedInvokeStartedDetails.builder()
                                .functionName(update.chainedInvokeOptions().functionName())
                                .input(EventInput.builder()
                                        .payload(update.payload())
                                        .build())
                                .build())
                        .build();
            case SUCCEED ->
                builder.eventType(EventType.CHAINED_INVOKE_SUCCEEDED)
                        .chainedInvokeSucceededDetails(ChainedInvokeSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload(
                                                operation.chainedInvokeDetails().result())
                                        .build())
                                .build())
                        .build();
            case FAIL ->
                builder.eventType(EventType.CHAINED_INVOKE_FAILED)
                        .chainedInvokeFailedDetails(ChainedInvokeFailedDetails.builder()
                                .error(EventError.builder()
                                        .payload(
                                                operation.chainedInvokeDetails().error())
                                        .build())
                                .build())
                        .build();

            default -> throw new IllegalArgumentException("Unsupported invoke action: " + update.action());
        };
    }

    private Event buildExecutionEvent(Event.Builder builder, OperationUpdate update) {
        return switch (update.action()) {
            case START ->
                builder.eventType(EXECUTION_STARTED)
                        .executionStartedDetails(
                                ExecutionStartedDetails.builder().build())
                        .build();
            case SUCCEED ->
                builder.eventType(EXECUTION_SUCCEEDED)
                        .executionSucceededDetails(ExecutionSucceededDetails.builder()
                                .result(EventResult.builder()
                                        .payload(update.payload())
                                        .build())
                                .build())
                        .build();
            case FAIL ->
                builder.eventType(EXECUTION_FAILED)
                        .executionFailedDetails(ExecutionFailedDetails.builder()
                                .error(EventError.builder()
                                        .payload(update.error())
                                        .build())
                                .build())
                        .build();
            default -> throw new IllegalArgumentException("Unsupported execution action: " + update.action());
        };
    }

    private Event buildCallbackEvent(Event.Builder builder, OperationUpdate update) {
        return switch (update.action()) {
            case START -> builder.eventType(CALLBACK_STARTED).build();
            case SUCCEED -> builder.eventType(CALLBACK_SUCCEEDED).build();
            case FAIL -> builder.eventType(CALLBACK_FAILED).build();
            default -> throw new IllegalArgumentException("Unsupported callback action: " + update.action());
        };
    }

    private Event buildContextEvent(Event.Builder builder, OperationUpdate update) {
        return switch (update.action()) {
            case START -> builder.eventType(EventType.CONTEXT_STARTED).build();
            case SUCCEED -> builder.eventType(EventType.CONTEXT_SUCCEEDED).build();
            case FAIL -> builder.eventType(EventType.CONTEXT_FAILED).build();
            default -> throw new IllegalArgumentException("Unsupported context action: " + update.action());
        };
    }

    private RetryDetails buildRetryDetails(Operation operation) {
        if (operation == null || operation.stepDetails() == null) {
            return RetryDetails.builder().currentAttempt(1).build();
        }
        var attempt = operation.stepDetails().attempt();
        return RetryDetails.builder()
                .currentAttempt(attempt != null ? attempt : 1)
                .build();
    }
}
