// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import software.amazon.awssdk.services.lambda.model.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

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
            case EXECUTION -> buildExecutionEvent(builder, update);
            default -> throw new IllegalArgumentException("Unsupported operation type: " + update.type());
        };
    }

    private Event buildStepEvent(Event.Builder builder, OperationUpdate update, Operation operation) {
        return switch (update.action()) {
            case START -> builder.eventType("StepStarted")
                    .stepStartedDetails(StepStartedDetails.builder().build())
                    .build();
            case SUCCEED -> builder.eventType("StepSucceeded")
                    .stepSucceededDetails(StepSucceededDetails.builder()
                            .result(EventResult.builder().payload(update.payload()).build())
                            .retryDetails(buildRetryDetails(operation))
                            .build())
                    .build();
            case FAIL -> builder.eventType("StepFailed")
                    .stepFailedDetails(StepFailedDetails.builder()
                            .error(EventError.builder().payload(update.error()).build())
                            .retryDetails(buildRetryDetails(operation))
                            .build())
                    .build();
            case RETRY -> builder.eventType("StepStarted")
                    .stepStartedDetails(StepStartedDetails.builder().build())
                    .build();
            default -> throw new IllegalArgumentException("Unsupported step action: " + update.action());
        };
    }

    @SuppressWarnings("unused") // operation param kept for API consistency
    private Event buildWaitEvent(Event.Builder builder, OperationUpdate update, Operation operation) {
        return switch (update.action()) {
            case START -> {
                var waitSeconds = update.waitOptions() != null ? update.waitOptions().waitSeconds() : 0;
                yield builder.eventType("WaitStarted")
                        .waitStartedDetails(WaitStartedDetails.builder()
                                .duration(waitSeconds)
                                .scheduledEndTimestamp(Instant.now().plusSeconds(waitSeconds))
                                .build())
                        .build();
            }
            case SUCCEED -> builder.eventType("WaitSucceeded")
                    .waitSucceededDetails(WaitSucceededDetails.builder().build())
                    .build();
            case CANCEL -> builder.eventType("WaitCancelled")
                    .waitCancelledDetails(WaitCancelledDetails.builder().build())
                    .build();
            default -> throw new IllegalArgumentException("Unsupported wait action: " + update.action());
        };
    }

    private Event buildExecutionEvent(Event.Builder builder, OperationUpdate update) {
        return switch (update.action()) {
            case START -> builder.eventType("ExecutionStarted")
                    .executionStartedDetails(ExecutionStartedDetails.builder().build())
                    .build();
            case SUCCEED -> builder.eventType("ExecutionSucceeded")
                    .executionSucceededDetails(ExecutionSucceededDetails.builder()
                            .result(EventResult.builder().payload(update.payload()).build())
                            .build())
                    .build();
            case FAIL -> builder.eventType("ExecutionFailed")
                    .executionFailedDetails(ExecutionFailedDetails.builder()
                            .error(EventError.builder().payload(update.error()).build())
                            .build())
                    .build();
            default -> throw new IllegalArgumentException("Unsupported execution action: " + update.action());
        };
    }

    private RetryDetails buildRetryDetails(Operation operation) {
        if (operation == null || operation.stepDetails() == null) {
            return RetryDetails.builder().currentAttempt(1).build();
        }
        var attempt = operation.stepDetails().attempt();
        return RetryDetails.builder().currentAttempt(attempt != null ? attempt : 1).build();
    }
}
