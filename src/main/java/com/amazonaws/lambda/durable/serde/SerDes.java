package com.amazonaws.lambda.durable.serde;

/**
 * Minimal serialization interface.
 */
public interface SerDes {
    String serialize(Object value);
    <T> T deserialize(String data, Class<T> type);
}
