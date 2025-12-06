package com.amazonaws.lambda.durable.serde;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class JacksonSerDes implements SerDes {
    private final ObjectMapper mapper;
    
    public JacksonSerDes() {
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    
    @Override
    public String serialize(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }
    
    @Override
    public <T> T deserialize(String data, Class<T> type) {
        if (data == null) return null;
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }
}
