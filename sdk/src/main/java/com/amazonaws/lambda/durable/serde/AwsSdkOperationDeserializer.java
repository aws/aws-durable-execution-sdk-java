package com.amazonaws.lambda.durable.serde;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.services.lambda.model.*;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

// TODO: Check how to serialize out of the box
public class AwsSdkOperationDeserializer extends JsonDeserializer<Operation> {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss.SSSSSSXXX")
            .toFormatter();

    @Override
    public Operation deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        var builder = Operation.builder()
                .id(node.get("Id").asText())
                .type(OperationType.fromValue(node.get("Type").asText()))
                .status(OperationStatus.fromValue(node.get("Status").asText()));

        // Handle optional fields
        if (node.has("ParentId")) {
            builder.parentId(node.get("ParentId").asText());
        }

        if (node.has("Name")) {
            builder.name(node.get("Name").asText());
        }

        if (node.has("SubType")) {
            builder.subType(node.get("SubType").asText());
        }

        if (node.has("StartTimestamp")) {
            builder.startTimestamp(Instant.from(TIMESTAMP_FORMATTER.parse(node.get("StartTimestamp").asText())));
        }

        if (node.has("EndTimestamp")) {
            builder.endTimestamp(Instant.from(TIMESTAMP_FORMATTER.parse(node.get("EndTimestamp").asText())));
        }

        // Handle ExecutionDetails
        if (node.has("ExecutionDetails")) {
            var details = node.get("ExecutionDetails");
            builder.executionDetails(ExecutionDetails.builder()
                    .inputPayload(details.has("InputPayload") ? details.get("InputPayload").asText() : null)
                    .build());
        }

        // Handle ContextDetails
        if (node.has("ContextDetails")) {
            builder.contextDetails(ContextDetails.builder()
                    // Add context-specific fields as needed based on ContextDetails structure
                    .build());
        }

        // Handle StepDetails
        if (node.has("StepDetails")) {
            var details = node.get("StepDetails");
            var stepDetailsBuilder = StepDetails.builder()
                    .result(details.has("Result") ? details.get("Result").asText() : null);

            // Handle attempt number if present
            if (details.has("Attempt")) {
                stepDetailsBuilder.attempt(details.get("Attempt").asInt());
            }

            // Handle error if present
            if (details.has("Error")) {
                var error = details.get("Error");
                stepDetailsBuilder.error(ErrorObject.builder()
                        .errorType(error.has("ErrorType") ? error.get("ErrorType").asText() : null)
                        .errorMessage(error.has("ErrorMessage") ? error.get("ErrorMessage").asText() : null)
                        .build());
            }

            builder.stepDetails(stepDetailsBuilder.build());
        }

        // Handle WaitDetails
        if (node.has("WaitDetails")) {
            var details = node.get("WaitDetails");
            var waitDetailsBuilder = WaitDetails.builder();

            // Handle scheduled end timestamp if present
            if (details.has("ScheduledEndTimestamp")) {
                waitDetailsBuilder.scheduledEndTimestamp(
                        Instant.from(TIMESTAMP_FORMATTER.parse(details.get("ScheduledEndTimestamp").asText())));
            }

            builder.waitDetails(waitDetailsBuilder.build());
        }

        // Handle CallbackDetails
        if (node.has("CallbackDetails")) {
            var details = node.get("CallbackDetails");
            var callbackDetailsBuilder = CallbackDetails.builder();

            // Handle callback ID if present
            if (details.has("CallbackId")) {
                callbackDetailsBuilder.callbackId(details.get("CallbackId").asText());
            }

            // Handle result if present
            if (details.has("Result")) {
                callbackDetailsBuilder.result(details.get("Result").asText());
            }

            // Handle error if present
            if (details.has("Error")) {
                var error = details.get("Error");
                callbackDetailsBuilder.error(ErrorObject.builder()
                        .errorType(error.has("ErrorType") ? error.get("ErrorType").asText() : null)
                        .errorMessage(error.has("ErrorMessage") ? error.get("ErrorMessage").asText() : null)
                        .build());
            }

            builder.callbackDetails(callbackDetailsBuilder.build());
        }

        // Handle ChainedInvokeDetails
        if (node.has("ChainedInvokeDetails")) {
            var details = node.get("ChainedInvokeDetails");
            var chainedInvokeDetailsBuilder = ChainedInvokeDetails.builder();

            // Handle result if present
            if (details.has("Result")) {
                chainedInvokeDetailsBuilder.result(details.get("Result").asText());
            }

            // Handle error if present
            if (details.has("Error")) {
                var error = details.get("Error");
                chainedInvokeDetailsBuilder.error(ErrorObject.builder()
                        .errorType(error.has("ErrorType") ? error.get("ErrorType").asText() : null)
                        .errorMessage(error.has("ErrorMessage") ? error.get("ErrorMessage").asText() : null)
                        .build());
            }

            builder.chainedInvokeDetails(chainedInvokeDetailsBuilder.build());
        }

        return builder.build();
    }
}
