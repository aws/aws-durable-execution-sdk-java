package com.amazonaws.lambda.durable.serde;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JacksonSerDes implements SerDes {
    private final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }
    
    @Override
    public <T> T deserialize(String data, Class<T> type) {
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }
}
