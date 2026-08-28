// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON helper for emitting insight records and measuring their serialized size.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class Json {
    // Register JavaTimeModule and emit ISO-8601 (not numeric timestamps) so SDK-default payload types such as
    // java.time.Instant/Duration in an included input/output/result serialize instead of throwing and silently
    // dropping the record.
    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json() {}

    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize insight record", e);
        }
    }

    /** UTF-8 byte length of the value's JSON, or {@code null} if it can't be serialized. */
    public static Integer byteSize(Object value) {
        try {
            return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Deep-copies JSON content (execution input/output and operation results) so a mutation by one exporter cannot leak
     * into another exporter's record. Maps and lists are rebuilt recursively. Immutable scalar values are shared. Other
     * serializable objects are converted into detached JSON-compatible object graphs, preserving their emitted JSON
     * while preventing mutable POJO state from being shared between exporters.
     */
    static Object deepCopyContent(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(e.getKey(), deepCopyContent(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object o : list) {
                copy.add(deepCopyContent(o));
            }
            return copy;
        }
        try {
            return MAPPER.convertValue(value, Object.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("failed to copy insight record content", e);
        }
    }
}
