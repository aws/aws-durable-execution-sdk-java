// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.filesystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.lambda.durable.exception.PayloadOffloadException;

/** Utilities for building compact structured previews for externally stored payloads. */
public final class PayloadPreview {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private PayloadPreview() {}

    /** Builds a preview from an object using include, exclude, mask, path-matching, and byte-budget rules. */
    public static Map<String, Object> buildPreview(Object value, PreviewConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        final JsonNode root;
        try {
            root = MAPPER.valueToTree(value);
        } catch (IllegalArgumentException e) {
            throw new PayloadOffloadException("Failed to convert value for preview generation", e);
        }
        return buildPreview(root, config);
    }

    /** Builds a preview from a JSON string. */
    public static Map<String, Object> buildPreviewFromJson(String value, PreviewConfig config) {
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        try {
            return buildPreview(MAPPER.readTree(value), config);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Map<String, Object> buildPreview(JsonNode root, PreviewConfig config) {
        if (root == null || !root.isObject()) {
            return null;
        }

        var pairs = new ArrayList<PreviewEntry>();
        collect(root, List.of(), false, config, pairs);
        if (pairs.isEmpty()) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (var pair : pairs) {
            var candidate = copy(result);
            insert(candidate, pair.path(), pair.value());
            if (serializedSize(candidate) > config.maxPreviewBytes()) {
                break;
            }
            result = candidate;
        }
        return result.isEmpty() ? null : result;
    }

    private static void collect(
            JsonNode node,
            List<String> pathPrefix,
            boolean inheritedInclude,
            PreviewConfig config,
            List<PreviewEntry> pairs) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            // Container arrays cannot be represented faithfully by the map-based preview shape without preserving
            // indices. Omit them instead of collapsing elements onto the same path and silently overwriting values.
            return;
        }
        if (!node.isObject()) {
            return;
        }

        for (var field : node.properties()) {
            var name = field.getKey();
            var path = append(pathPrefix, name);
            var masked = isMatched(path, config.mask());
            var excluded = isMatched(path, config.exclude());
            var explicitlyIncluded = isMatched(path, config.include());
            var visible = !excluded
                    && (masked || inheritedInclude || config.mode() == PreviewMode.INCLUDE_ALL || explicitlyIncluded);

            if (!visible) {
                if (!excluded) {
                    collect(field.getValue(), path, false, config, pairs);
                }
                continue;
            }
            if (masked) {
                pairs.add(new PreviewEntry(path, config.maskString()));
            } else if (isScalarArray(field.getValue())) {
                pairs.add(new PreviewEntry(path, MAPPER.convertValue(field.getValue(), Object.class)));
            } else if (field.getValue().isContainerNode()) {
                collect(field.getValue(), path, inheritedInclude || explicitlyIncluded, config, pairs);
            } else {
                pairs.add(new PreviewEntry(path, MAPPER.convertValue(field.getValue(), Object.class)));
            }
        }
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

    private static boolean isMatched(List<String> path, List<PreviewField> fields) {
        for (var field : fields) {
            if (field.match() == FieldMatchMode.PATH) {
                if (path.equals(field.pathSegments())) {
                    return true;
                }
            } else if (path.contains(field.name())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> append(List<String> path, String field) {
        var result = new ArrayList<>(path);
        result.add(field);
        return List.copyOf(result);
    }

    private static int serializedSize(Map<String, Object> preview) {
        try {
            return MAPPER.writeValueAsBytes(preview).length;
        } catch (JsonProcessingException e) {
            throw new PayloadOffloadException("Failed to measure preview size", e);
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
    private static void insert(Map<String, Object> result, List<String> path, Object value) {
        Map<String, Object> current = result;
        for (int index = 0; index < path.size() - 1; index++) {
            var existing = current.get(path.get(index));
            if (!(existing instanceof Map<?, ?>)) {
                existing = new LinkedHashMap<String, Object>();
                current.put(path.get(index), existing);
            }
            current = (Map<String, Object>) existing;
        }
        current.put(path.get(path.size() - 1), value);
    }

    private record PreviewEntry(List<String> path, Object value) {}
}
