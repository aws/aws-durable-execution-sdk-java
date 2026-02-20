// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
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
        ErrorObject error = null;

        for (var event : events) {
            var eventType = event.eventType();
            var operationId = event.id();

            // Group events by operation
            if (operationId != null) {
                operationEvents
                        .computeIfAbsent(operationId, k -> new ArrayList<>())
                        .add(event);
            }

            switch (eventType) {
                case EXECUTION_STARTED, INVOCATION_COMPLETED -> {
                    // Execution started - no action needed, just track the event
                }
                case EXECUTION_SUCCEEDED -> {
                    status = ExecutionStatus.SUCCEEDED;
                    var details = event.executionSucceededDetails();
                    if (details != null
                            && details.result() != null
                            && details.result().payload() != null) {
                        result = details.result().payload();
                    }
                }
                case EXECUTION_FAILED -> {
                    status = ExecutionStatus.FAILED;
                    var details = event.executionFailedDetails();
                    if (details != null
                            && details.error() != null
                            && details.error().payload() != null) {
                        error = details.error().payload();
                    }
                }
                case EXECUTION_TIMED_OUT -> {
                    status = ExecutionStatus.FAILED;
                    var details = event.executionTimedOutDetails();
                    if (details != null
                            && details.error() != null
                            && details.error().payload() != null) {
                        error = details.error().payload();
                    }
                }
                case EXECUTION_STOPPED -> {
                    status = ExecutionStatus.FAILED;

                    var details = event.executionStoppedDetails();
                    if (details != null
                            && details.error() != null
                            && details.error().payload() != null) {
                        error = details.error().payload();
                    }
                }
                case STEP_STARTED -> {
                    if (operationId != null) {
                        operations.putIfAbsent(
                                operationId,
                                createStepOperation(operationId, event.name(), null, OperationStatus.STARTED, 1));
                    }
                }
                case STEP_SUCCEEDED -> {
                    if (operationId != null) {
                        var details = event.stepSucceededDetails();
                        var stepResult = details != null && details.result() != null
                                ? details.result().payload()
                                : null;
                        var attempt = details != null && details.retryDetails() != null
                                ? details.retryDetails().currentAttempt()
                                : 1;
                        operations.put(
                                operationId,
                                createStepOperation(
                                        operationId, event.name(), stepResult, OperationStatus.SUCCEEDED, attempt));
                    }
                }
                case STEP_FAILED -> {
                    if (operationId != null) {
                        var details = event.stepFailedDetails();
                        var attempt = details != null && details.retryDetails() != null
                                ? details.retryDetails().currentAttempt()
                                : 1;
                        operations.put(
                                operationId,
                                createStepOperation(operationId, event.name(), null, OperationStatus.FAILED, attempt));
                    }
                }

                case WAIT_STARTED -> {
                    if (operationId != null) {
                        operations.putIfAbsent(
                                operationId,
                                createWaitOperation(operationId, event.name(), OperationStatus.STARTED, event));
                    }
                }
                case WAIT_SUCCEEDED -> {
                    if (operationId != null) {
                        operations.put(
                                operationId,
                                createWaitOperation(operationId, event.name(), OperationStatus.SUCCEEDED, event));
                    }
                }
                case WAIT_CANCELLED -> {
                    if (operationId != null) {
                        operations.put(
                                operationId,
                                createWaitOperation(operationId, event.name(), OperationStatus.CANCELLED, event));
                    }
                }

                case CALLBACK_STARTED -> {
                    if (operationId != null) {
                        operations.putIfAbsent(
                                operationId,
                                createCallbackOperation(operationId, event.name(), OperationStatus.STARTED, event));
                    }
                }
                case CALLBACK_SUCCEEDED -> {
                    if (operationId != null) {
                        operations.put(
                                operationId,
                                createCallbackOperation(operationId, event.name(), OperationStatus.SUCCEEDED, event));
                    }
                }
                case CALLBACK_FAILED -> {
                    if (operationId != null) {
                        operations.put(
                                operationId,
                                createCallbackOperation(operationId, event.name(), OperationStatus.FAILED, event));
                    }
                }
                case CALLBACK_TIMED_OUT -> {
                    if (operationId != null) {
                        operations.put(
                                operationId,
                                createCallbackOperation(operationId, event.name(), OperationStatus.TIMED_OUT, event));
                    }
                }

                case UNKNOWN_TO_SDK_VERSION -> {
                    // Unknown event type - log and ignore gracefully
                }

                case CONTEXT_STARTED -> {
                    if (operationId != null) {
                        operations.putIfAbsent(
                                operationId,
                                createContextOperation(operationId, event.name(), OperationStatus.STARTED, event));
                    }
                }
                case CONTEXT_SUCCEEDED -> {
                    if (operationId != null) {
                        operations.put(
                                operationId,
                                createContextOperation(operationId, event.name(), OperationStatus.SUCCEEDED, event));
                    }
                }
                case CONTEXT_FAILED -> {
                    if (operationId != null) {
                        operations.put(
                                operationId,
                                createContextOperation(operationId, event.name(), OperationStatus.FAILED, event));
                    }
                }

                case CHAINED_INVOKE_STARTED,
                        CHAINED_INVOKE_SUCCEEDED,
                        CHAINED_INVOKE_FAILED,
                        CHAINED_INVOKE_TIMED_OUT,
                        CHAINED_INVOKE_STOPPED -> {
                    if (operationId != null) {
                        operations.putIfAbsent(operationId, createInvokeOperation(operationId, event));
                    }
                }

                default -> throw new UnsupportedOperationException("Unknown operation: " + eventType);
            }
        }

        // Build TestOperations with events
        var testOperations = new ArrayList<TestOperation>();
        for (var entry : operations.entrySet()) {
            var opEvents = operationEvents.getOrDefault(entry.getKey(), List.of());
            testOperations.add(new TestOperation(entry.getValue(), opEvents, serDes));
        }

        return new TestResult<>(status, result, error, testOperations, events, serDes);
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

    private Operation createCallbackOperation(String id, String name, OperationStatus status, Event event) {
        var builder = CallbackDetails.builder();

        // Extract callback ID and details from event
        if (event.callbackStartedDetails() != null) {
            var details = event.callbackStartedDetails();
            if (details.callbackId() != null) {
                builder.callbackId(details.callbackId());
            }
        } else if (event.callbackSucceededDetails() != null) {
            var details = event.callbackSucceededDetails();
            // CallbackSucceededDetails doesn't have callbackId, need to get it from started event
            if (details.result() != null && details.result().payload() != null) {
                builder.result(details.result().payload());
            }
        } else if (event.callbackFailedDetails() != null) {
            var details = event.callbackFailedDetails();
            // CallbackFailedDetails doesn't have callbackId, need to get it from started event
            if (details.error() != null && details.error().payload() != null) {
                builder.error(ErrorObject.builder()
                        .errorType(details.error().payload().errorType())
                        .errorMessage(details.error().payload().errorMessage())
                        .build());
            }
        }

        return Operation.builder()
                .id(id)
                .name(name)
                .status(status)
                .type(OperationType.CALLBACK)
                .callbackDetails(builder.build())
                .build();
    }

    private Operation createInvokeOperation(String id, Event event) {
        var builder = ChainedInvokeDetails.builder();

        OperationStatus status =
                switch (event.eventType()) {
                    case CHAINED_INVOKE_STARTED -> OperationStatus.STARTED;
                    case CHAINED_INVOKE_SUCCEEDED -> {
                        var details = event.callbackSucceededDetails();
                        if (details != null
                                && details.result() != null
                                && details.result().payload() != null) {
                            builder.result(details.result().payload());
                        }
                        yield OperationStatus.SUCCEEDED;
                    }
                    case CHAINED_INVOKE_FAILED -> {
                        var details = event.callbackFailedDetails();
                        if (details != null
                                && details.error() != null
                                && details.error().payload() != null) {
                            builder.error(details.error().payload());
                        }
                        yield OperationStatus.FAILED;
                    }
                    case CHAINED_INVOKE_STOPPED -> {
                        var details = event.chainedInvokeStoppedDetails();
                        if (details != null
                                && details.error() != null
                                && details.error().payload() != null) {
                            builder.error(details.error().payload());
                        }

                        yield OperationStatus.STOPPED;
                    }
                    case CHAINED_INVOKE_TIMED_OUT -> {
                        var details = event.chainedInvokeTimedOutDetails();
                        if (details != null
                                && details.error() != null
                                && details.error().payload() != null) {
                            builder.error(details.error().payload());
                        }
                        yield OperationStatus.TIMED_OUT;
                    }
                    default ->
                        throw new UnsupportedOperationException(
                                "Unknown chained invocation operation: " + event.eventType());
                };

        return Operation.builder()
                .id(id)
                .name(event.name())
                .status(status)
                .type(OperationType.CHAINED_INVOKE)
                .chainedInvokeDetails(builder.build())
                .build();
    }

    private Operation createContextOperation(String id, String name, OperationStatus status, Event event) {
        var builder = ContextDetails.builder();

        if (event.contextSucceededDetails() != null) {
            var details = event.contextSucceededDetails();
            if (details.result() != null && details.result().payload() != null) {
                builder.result(details.result().payload());
            }
        } else if (event.contextFailedDetails() != null) {
            var details = event.contextFailedDetails();
            if (details.error() != null && details.error().payload() != null) {
                builder.error(details.error().payload());
            }
        }

        return Operation.builder()
                .id(id)
                .name(name)
                .status(status)
                .type(OperationType.CONTEXT)
                .contextDetails(builder.build())
                .build();
    }
}
