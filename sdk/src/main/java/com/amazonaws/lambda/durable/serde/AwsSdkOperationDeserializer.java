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
        
        if (node.has("Name")) {
            builder.name(node.get("Name").asText());
        }
        
        if (node.has("StartTimestamp")) {
            builder.startTimestamp(Instant.from(TIMESTAMP_FORMATTER.parse(node.get("StartTimestamp").asText())));
        }
        
        if (node.has("EndTimestamp")) {
            builder.endTimestamp(Instant.from(TIMESTAMP_FORMATTER.parse(node.get("EndTimestamp").asText())));
        }
        
        if (node.has("ExecutionDetails")) {
            var details = node.get("ExecutionDetails");
            builder.executionDetails(ExecutionDetails.builder()
                .inputPayload(details.has("InputPayload") ? details.get("InputPayload").asText() : null)
                .build());
        }
        
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
        
        return builder.build();
    }
}
