package com.amazonaws.lambda.durable.serde;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

public class AwsSdkV2Module extends SimpleModule {

    public AwsSdkV2Module(ObjectMapper sharedMapper) {
        super("AwsSdkV2Module");
        addDeserializer(Operation.class, new OperationDeserializer(sharedMapper));
        addDeserializer(ErrorObject.class, new ErrorObjectDeserializer(sharedMapper));
        addSerializer(ErrorObject.class, new ErrorObjectSerializer());
    }

    private static class OperationDeserializer extends JsonDeserializer<Operation> {
        private final ObjectMapper mapper;

        OperationDeserializer(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Operation deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();

            // See
            // https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-serialization-changes.html
            return mapper.readValue(node.toString(), Operation.serializableBuilderClass()).build();
        }
    }

    private static class ErrorObjectDeserializer extends JsonDeserializer<ErrorObject> {
        private final ObjectMapper mapper;

        ErrorObjectDeserializer(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public ErrorObject deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();

            // See
            // https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-serialization-changes.html
            return mapper.readValue(node.toString(), ErrorObject.serializableBuilderClass()).build();
        }
    }

    private static class ErrorObjectSerializer extends JsonSerializer<ErrorObject> {
        @Override
        public void serialize(ErrorObject value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            // Use toBuilder() method as recommended by AWS SDK v2 documentation
            // See
            // https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-serialization-changes.html
            serializers.defaultSerializeValue(value.toBuilder(), gen);
        }
    }
}
