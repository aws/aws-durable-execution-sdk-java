// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.lambda.durable.exception.SerDesException;

/** Utilities for building compact structured previews for externally stored SerDes payloads. */
public final class SerDesPreview {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SerDesPreview() {}

    /**
     * Builds a preview from an object using include, exclude, mask, path-matching, and byte-budget rules.
     *
     * <p>Object fields are traversed in their Jackson serialization order. Arrays are flattened into their containing
     * path, matching the Python and TypeScript preview behavior. Fields whose names contain dots are skipped because
     * they cannot be distinguished from dot-separated paths.
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

        var pairs = new ArrayList<PreviewEntry>();
        collect(root, "", config, pairs);
        if (pairs.isEmpty()) {
            return null;
        }

        var accepted = new ArrayList<PreviewEntry>();
        int estimatedSize = 2;
        for (var pair : pairs) {
            int entrySize = previewEntrySize(pair);
            if (estimatedSize + entrySize > config.maxPreviewBytes()) {
                break;
            }
            accepted.add(pair);
            estimatedSize += entrySize;
        }
        if (accepted.isEmpty()) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (var pair : accepted) {
            insert(result, pair.path(), pair.value());
        }
        return result;
    }

    private static void collect(JsonNode node, String pathPrefix, PreviewConfig config, List<PreviewEntry> pairs) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (var item : node) {
                collect(item, pathPrefix, config, pairs);
            }
            return;
        }
        if (!node.isObject()) {
            return;
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
                if (!excluded) {
                    collect(field.getValue(), path, config, pairs);
                }
                continue;
            }
            if (masked) {
                pairs.add(new PreviewEntry(path, config.maskString()));
            } else if (field.getValue().isContainerNode()) {
                collect(field.getValue(), path, config, pairs);
            } else {
                pairs.add(new PreviewEntry(path, MAPPER.convertValue(field.getValue(), Object.class)));
            }
        }
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

    private static int previewEntrySize(PreviewEntry entry) {
        try {
            var serialized =
                    MAPPER.writeValueAsString(entry.path()) + ":" + MAPPER.writeValueAsString(entry.value()) + ",";
            return serialized.getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            throw new SerDesException("Failed to estimate preview size", e);
        }
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

    private record PreviewEntry(String path, Object value) {}
}
