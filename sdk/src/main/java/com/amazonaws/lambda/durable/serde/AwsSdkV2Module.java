package com.amazonaws.lambda.durable.serde;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import software.amazon.awssdk.services.lambda.model.Operation;

import java.io.IOException;

public class AwsSdkV2Module extends SimpleModule {

    public AwsSdkV2Module(ObjectMapper sharedMapper) {
        super("AwsSdkV2Module");
        addDeserializer(Operation.class, new OperationDeserializer(sharedMapper));
    }

    private static class OperationDeserializer extends JsonDeserializer<Operation> {
        private final ObjectMapper mapper;

        OperationDeserializer(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Operation deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();

            // See https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-serialization-changes.html
            return mapper.readValue(node.toString(), Operation.serializableBuilderClass()).build();
        }
    }
}
