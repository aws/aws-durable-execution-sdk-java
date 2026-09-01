// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.lambda.durable.exception.SerDesException;

/** Utilities for building compact structured previews for externally stored SerDes payloads. */
public final class SerDesPreview {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SerDesPreview() {}

    /**
     * Builds a preview from an object using include, exclude, mask, path-matching, and byte-budget rules.
     *
     * <p>Object fields are traversed in their Jackson serialization order. Object arrays are flattened into their
     * containing path, while scalar arrays are preserved as field values, matching the Python and TypeScript preview
     * behavior. Fields whose names contain dots are skipped because they cannot be distinguished from dot-separated
     * paths.
     *
     * @return a nested preview map, or {@code null} when no fields are visible
     */
    public static Map<String, Object> buildPreview(Object value, PreviewConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        final JsonNode root;
        try {
            root = MAPPER.valueToTree(value);
        } catch (IllegalArgumentException e) {
            throw new SerDesException("Failed to convert value for preview generation", e);
        }
        return buildPreview(root, config);
    }

    /**
     * Builds a preview from a JSON string.
     *
     * <p>This is used by {@link FileSystemSerDesStage.Builder#previewConfig(PreviewConfig)}, because a pipeline stage
     * receives the serialized string produced by the preceding stage.
     *
     * @return a nested preview map, or {@code null} when no fields are visible
     */
    public static Map<String, Object> buildPreviewFromJson(String value, PreviewConfig config) {
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        try {
            return buildPreview(MAPPER.readTree(value), config);
        } catch (JsonProcessingException e) {
            throw new SerDesException("Built-in preview generation requires a JSON stage value", e);
        }
    }

    private static Map<String, Object> buildPreview(JsonNode root, PreviewConfig config) {
        if (root == null || !root.isObject()) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        collect(root, "", config, result);
        return result.isEmpty() ? null : result;
    }

    private static boolean collect(JsonNode node, String pathPrefix, PreviewConfig config, Map<String, Object> result) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isArray()) {
            for (var item : node) {
                if (!collect(item, pathPrefix, config, result)) {
                    return false;
                }
            }
            return true;
        }
        if (!node.isObject()) {
            return true;
        }

        for (var field : node.properties()) {
            var name = field.getKey();
            if (name.contains(".")) {
                continue;
            }
            var path = pathPrefix.isEmpty() ? name : pathPrefix + "." + name;
            var masked = isMatched(path, config.mask());
            var excluded = isMatched(path, config.exclude());
            var visible = !excluded
                    && (masked || config.mode() == PreviewMode.INCLUDE_ALL || isMatched(path, config.include()));

            if (!visible) {
                if (!excluded && !collect(field.getValue(), path, config, result)) {
                    return false;
                }
                continue;
            }
            if (masked) {
                if (!tryInsert(result, path, config.maskString(), config.maxPreviewBytes())) {
                    return false;
                }
            } else if (isScalarArray(field.getValue())) {
                if (!tryInsert(result, path, field.getValue(), config.maxPreviewBytes())) {
                    return false;
                }
            } else if (field.getValue().isContainerNode()) {
                if (!collect(field.getValue(), path, config, result)) {
                    return false;
                }
            } else {
                if (!tryInsert(result, path, field.getValue(), config.maxPreviewBytes())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean tryInsert(Map<String, Object> result, String path, Object value, int maxPreviewBytes) {
        var candidate = copy(result);
        insert(candidate, path, value);
        if (serializedSize(candidate) > maxPreviewBytes) {
            return false;
        }
        var converted = value instanceof JsonNode node ? MAPPER.convertValue(node, Object.class) : value;
        insert(result, path, converted);
        return true;
    }

    private static boolean isScalarArray(JsonNode node) {
        if (!node.isArray()) {
            return false;
        }
        for (var item : node) {
            if (item.isContainerNode()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMatched(String path, List<PreviewField> fields) {
        for (var field : fields) {
            if (field.match() == FieldMatchMode.PATH) {
                if (path.equals(field.name())) {
                    return true;
                }
            } else {
                for (var segment : path.split("\\.")) {
                    if (segment.equals(field.name())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int serializedSize(Map<String, Object> preview) {
        try {
            return MAPPER.writeValueAsBytes(preview).length;
        } catch (JsonProcessingException e) {
            throw new SerDesException("Failed to measure preview size", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            var value = entry.getValue();
            copy.put(entry.getKey(), value instanceof Map<?, ?> nested ? copy((Map<String, Object>) nested) : value);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> result, String path, Object value) {
        var parts = path.split("\\.");
        Map<String, Object> current = result;
        for (int index = 0; index < parts.length - 1; index++) {
            var existing = current.get(parts[index]);
            if (!(existing instanceof Map<?, ?>)) {
                existing = new LinkedHashMap<String, Object>();
                current.put(parts[index], existing);
            }
            current = (Map<String, Object>) existing;
        }
        current.put(parts[parts.length - 1], value);
    }
}
