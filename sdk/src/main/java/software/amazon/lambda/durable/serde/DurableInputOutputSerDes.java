// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Serializer/Deserializer for Durable Execution Input and Output objects. This is for INTERNAL use only - handles
 * Lambda Durable Functions backend protocol.
 *
 * <p>Customer-facing serialization uses SerDes from DurableConfig.
 */
public class DurableInputOutputSerDes implements SerDes {
    private final ObjectMapper objectMapper = createObjectMapper(); // Internal ObjectMapper
    private final TypeFactory typeFactory = objectMapper.getTypeFactory();
    private final Map<Type, JavaType> typeCache = new ConcurrentHashMap<>();

    /**
     * Creates ObjectMapper for DAR backend communication (internal use only). This is for INTERNAL use only - handles
     * Lambda Durable Functions backend protocol.
     *
     * <p>Customer-facing serialization uses SerDes from DurableConfig.
     *
     * @return Configured ObjectMapper for durable backend communication
     */
    static ObjectMapper createObjectMapper() {
        var dateModule = new SimpleModule();
        dateModule.addDeserializer(Date.class, new JsonDeserializer<>() {
            @Override
            public Date deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                    throws IOException {
                // Timestamp is a double value represent seconds since epoch.
                var timestamp = jsonParser.getDoubleValue();
                // Date expects milliseconds since epoch, so multiply by 1000.
                return new Date((long) (timestamp * 1000));
            }
        });
        dateModule.addSerializer(Date.class, new JsonSerializer<>() {
            @Override
            public void serialize(Date date, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
                    throws IOException {
                // Timestamp should be a double value representing seconds since epoch, so
                // convert from milliseconds.
                double timestamp = date.getTime() / 1000.0;
                jsonGenerator.writeNumber(timestamp);
            }
        });

        // Needed for deserialization of timestamps for some SDK v2 objects
        dateModule.addDeserializer(Instant.class, new JsonDeserializer<>() {
            private static final DateTimeFormatter TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss.SSSSSSXXX")
                    .toFormatter();

            @Override
            public Instant deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                    throws IOException {
                if (jsonParser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                    return Instant.ofEpochMilli(jsonParser.getLongValue());
                }
                var timestampStr = jsonParser.getValueAsString();
                return Instant.from(TIMESTAMP_FORMATTER.parse(timestampStr));
            }
        });

        return JsonMapper.builder()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Looks pretty, and probably needed for tests to be deterministic.
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                // Data passed over the wire from the backend is UpperCamelCase
                .propertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
                .addModule(new JavaTimeModule())
                .addModule(dateModule)
                .addModule(new AwsSdkV2Module())
                .build();
    }

    /**
     * Serializes an object to a JSON string.
     *
     * @param value the object to serialize
     * @return the JSON string representation, or null if value is null
     */
    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            ExceptionHelper.sneakyThrow(e);
            return null;
        }
    }

    /**
     * Deserializes a JSON string to DurableExecutionInput object
     *
     * @param data the JSON string to deserialize
     * @param typeToken the type token of DurableExecutionInput
     * @return the deserialized object, or null if data is null
     */
    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }
        try {
            JavaType javaType = typeCache.computeIfAbsent(typeToken.getType(), typeFactory::constructType);
            return objectMapper.readValue(data, javaType);
        } catch (IOException e) {
            ExceptionHelper.sneakyThrow(e);
            return null;
        }
    }
}
