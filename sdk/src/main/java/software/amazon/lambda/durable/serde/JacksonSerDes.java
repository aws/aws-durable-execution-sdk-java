// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * Jackson-based implementation of {@link SerDes}.
 *
 * <p>This implementation uses Jackson's ObjectMapper for JSON serialization and deserialization, with support for both
 * simple types via {@link Class} and complex generic types via {@link TypeToken}.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Java 8 time types support (Duration, Instant, LocalDateTime, etc.)
 *   <li>Dates serialized as ISO-8601 strings (not timestamps)
 *   <li>Unknown properties ignored during deserialization
 *   <li>Type cache for improved performance with generic types
 * </ul>
 */
public class JacksonSerDes implements SerDes {
    private final ObjectMapper mapper;
    private final TypeFactory typeFactory;
    private final Map<Type, JavaType> typeCache;

    public JacksonSerDes() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.typeFactory = mapper.getTypeFactory();
        this.typeCache = new ConcurrentHashMap<>();
    }

    @Override
    public String serialize(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new SerDesException(
                    "Serialization failed for type: " + value.getClass().getName(), e);
        }
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) return null;

        try {
            // Convert TypeToken to Jackson's JavaType using TypeFactory
            // Cache to avoid repeated reflection overhead
            JavaType javaType = typeCache.computeIfAbsent(typeToken.getType(), type -> typeFactory.constructType(type));
            return mapper.readValue(data, javaType);
        } catch (Exception e) {
            throw new SerDesException(
                    "Deserialization failed for type: " + typeToken.getType().getTypeName(), e);
        }
    }
}
