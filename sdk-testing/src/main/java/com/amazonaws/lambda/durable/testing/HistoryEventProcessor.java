// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.awssdk.services.lambda.model.WaitDetails;

public class HistoryEventProcessor {
    private final JacksonSerDes serDes = new JacksonSerDes();

    public <O> TestResult<O> processEvents(List<Event> events, Class<O> outputType) {
        var operations = new HashMap<String, Operation>();
        var operationEvents = new HashMap<String, List<Event>>();
        var status = ExecutionStatus.PENDING;
        String result = null;

        for (var event : events) {
            var eventType = event.eventTypeAsString();
            var operationId = event.id();

            // Group events by operation
            if (operationId != null) {
                operationEvents
                        .computeIfAbsent(operationId, k -> new ArrayList<>())
                        .add(event);
            }

            // Extract execution result
            if ("ExecutionSucceeded".equals(eventType)) {
                status = ExecutionStatus.SUCCEEDED;
                var details = event.executionSucceededDetails();
                if (details != null
                        && details.result() != null
                        && details.result().payload() != null) {
                    result = details.result().payload();
                }
            } else if ("ExecutionFailed".equals(eventType)) {
                status = ExecutionStatus.FAILED;
            }

            // Process step events
            if ("StepStarted".equals(eventType) && operationId != null) {
                operations.putIfAbsent(
                        operationId, createStepOperation(operationId, event.name(), null, OperationStatus.STARTED, 1));
            } else if ("StepSucceeded".equals(eventType) && operationId != null) {
                var details = event.stepSucceededDetails();
                var stepResult = details != null && details.result() != null
                        ? details.result().payload()
                        : null;
                var attempt = details != null && details.retryDetails() != null
                        ? details.retryDetails().currentAttempt()
                        : 1;
                operations.put(
                        operationId,
                        createStepOperation(operationId, event.name(), stepResult, OperationStatus.SUCCEEDED, attempt));
            } else if ("StepFailed".equals(eventType) && operationId != null) {
                var details = event.stepFailedDetails();
                var attempt = details != null && details.retryDetails() != null
                        ? details.retryDetails().currentAttempt()
                        : 1;
                operations.put(
                        operationId,
                        createStepOperation(operationId, event.name(), null, OperationStatus.FAILED, attempt));
            }

            // Process wait events
            if ("WaitStarted".equals(eventType) && operationId != null) {
                operations.putIfAbsent(
                        operationId, createWaitOperation(operationId, event.name(), OperationStatus.STARTED, event));
            } else if ("WaitSucceeded".equals(eventType) && operationId != null) {
                operations.put(
                        operationId, createWaitOperation(operationId, event.name(), OperationStatus.SUCCEEDED, event));
            } else if ("WaitCancelled".equals(eventType) && operationId != null) {
                operations.put(
                        operationId, createWaitOperation(operationId, event.name(), OperationStatus.CANCELLED, event));
            }
        }

        // Build TestOperations with events
        var testOperations = new ArrayList<TestOperation>();
        for (var entry : operations.entrySet()) {
            var opEvents = operationEvents.getOrDefault(entry.getKey(), List.of());
            testOperations.add(new TestOperation(entry.getValue(), opEvents, serDes));
        }

        return new TestResult<>(status, result, null, testOperations, events, serDes);
    }

    private Operation createStepOperation(
            String id, String name, String stepResult, OperationStatus status, Integer attempt) {
        var stepDetails = StepDetails.builder()
                .result(stepResult)
                .attempt(attempt != null ? attempt : 1)
                .build();

        return Operation.builder()
                .id(id)
                .name(name)
                .status(status)
                .type(OperationType.STEP)
                .stepDetails(stepDetails)
                .build();
    }

    private Operation createWaitOperation(String id, String name, OperationStatus status, Event event) {
        var builder = WaitDetails.builder();
        if (event.waitStartedDetails() != null) {
            builder.scheduledEndTimestamp(event.waitStartedDetails().scheduledEndTimestamp());
        }

        return Operation.builder()
                .id(id)
                .name(name)
                .status(status)
                .type(OperationType.WAIT)
                .waitDetails(builder.build())
                .build();
    }
}
