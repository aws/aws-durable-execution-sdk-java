// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
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
        if (config.maxPreviewBytes() == 0) {
            return null;
        }
        var producerFailure = new AtomicReference<Throwable>();
        var input = new PipedInputStream(8 * 1024);
        try {
            var output = new PipedOutputStream(input);
            var producer = new Thread(
                    () -> {
                        try (output) {
                            MAPPER.writeValue(output, value);
                        } catch (Throwable failure) {
                            producerFailure.set(failure);
                        }
                    },
                    "durable-serdes-preview");
            producer.setDaemon(true);
            producer.start();

            StreamingPreview preview;
            Throwable consumerFailure = null;
            try (input;
                    var parser = previewJsonFactory(config).createParser(input)) {
                preview = collectStreamingPreview(parser, config);
            } catch (Throwable failure) {
                preview = null;
                consumerFailure = failure;
            } finally {
                joinProducer(producer);
            }

            var serializationFailure = producerFailure.get();
            if (consumerFailure != null) {
                throw new SerDesException("Failed to convert value for preview generation", consumerFailure);
            }
            if (serializationFailure != null
                    && (preview.fullyConsumed() || !isClosedPipeFailure(serializationFailure))) {
                throw new SerDesException("Failed to convert value for preview generation", serializationFailure);
            }
            return preview.result();
        } catch (IOException e) {
            throw new SerDesException("Failed to stream value for preview generation", e);
        }
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
        if (config.maxPreviewBytes() == 0) {
            return null;
        }
        try (var parser = previewJsonFactory(config).createParser(value)) {
            return collectStreamingPreview(parser, config).result();
        } catch (IOException e) {
            throw new SerDesException("Built-in preview generation requires a JSON stage value", e);
        }
    }

    private static StreamingPreview collectStreamingPreview(JsonParser parser, PreviewConfig config)
            throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            var rootToken = parser.nextToken();
            if (rootToken == null) {
                throw new IOException("Expected a JSON value");
            }
            if (rootToken != JsonToken.START_OBJECT) {
                parser.skipChildren();
                if (parser.nextToken() != null) {
                    throw new IOException("Unexpected trailing content after preview JSON value");
                }
                return new StreamingPreview(null, true);
            }
            var fullyConsumed = collectObject(parser, "", config, result);
            if (fullyConsumed && parser.nextToken() != null) {
                throw new IOException("Unexpected trailing content after preview JSON value");
            }
            return new StreamingPreview(result.isEmpty() ? null : result, fullyConsumed);
        } catch (StreamConstraintsException e) {
            return new StreamingPreview(result.isEmpty() ? null : result, false);
        }
    }

    private static JsonFactory previewJsonFactory(PreviewConfig config) {
        var maxTokenLength = config.maxPreviewBytes();
        var constraints = StreamReadConstraints.builder()
                .maxStringLength(maxTokenLength)
                .maxNameLength(maxTokenLength)
                .maxNumberLength(maxTokenLength)
                .build();
        return JsonFactory.builder().streamReadConstraints(constraints).build();
    }

    private static void joinProducer(Thread producer) {
        var interrupted = false;
        while (producer.isAlive()) {
            try {
                producer.join();
            } catch (InterruptedException e) {
                interrupted = true;
                producer.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isClosedPipeFailure(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            var message = current.getMessage();
            if (current instanceof IOException
                    && message != null
                    && (message.contains("Pipe closed")
                            || message.contains("Pipe broken")
                            || message.contains("Read end dead"))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> buildPreview(JsonNode root, PreviewConfig config) {
        if (root == null || !root.isObject()) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        collectTree(root, "", config, result);
        return result.isEmpty() ? null : result;
    }

    private static boolean collectObject(
            JsonParser parser, String pathPrefix, PreviewConfig config, Map<String, Object> result) throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.END_OBJECT) {
            if (token != JsonToken.FIELD_NAME) {
                throw new IOException("Expected a JSON object field");
            }
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if (valueToken == null) {
                throw new IOException("Unexpected end of JSON object");
            }
            if (name.contains(".")) {
                parser.skipChildren();
                continue;
            }

            var path = pathPrefix.isEmpty() ? name : pathPrefix + "." + name;
            var masked = isMatched(path, config.mask());
            var excluded = isMatched(path, config.exclude());
            var visible = !excluded
                    && (masked || config.mode() == PreviewMode.INCLUDE_ALL || isMatched(path, config.include()));

            if (excluded) {
                parser.skipChildren();
                continue;
            }
            if (masked) {
                parser.skipChildren();
                if (!tryInsert(result, path, config.maskString(), config.maxPreviewBytes())) {
                    return false;
                }
                continue;
            }
            if (!visible) {
                if (!collectDescendants(parser, valueToken, path, config, result)) {
                    return false;
                }
                continue;
            }

            if (!collectVisibleValue(parser, valueToken, path, config, result)) {
                return false;
            }
        }
        if (token == null) {
            throw new IOException("Unexpected end of JSON object");
        }
        return true;
    }

    private static boolean collectVisibleValue(
            JsonParser parser, JsonToken token, String path, PreviewConfig config, Map<String, Object> result)
            throws IOException {
        if (token == JsonToken.START_OBJECT) {
            return collectObject(parser, path, config, result);
        }
        if (token == JsonToken.START_ARRAY) {
            return collectVisibleArray(parser, path, config, result);
        }
        return tryInsert(result, path, scalarValue(parser, token), config.maxPreviewBytes());
    }

    private static boolean collectVisibleArray(
            JsonParser parser, String path, PreviewConfig config, Map<String, Object> result) throws IOException {
        var scalarValues = new ArrayList<Object>();
        var containsContainer = false;
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
            if (token == JsonToken.START_OBJECT) {
                containsContainer = true;
                scalarValues.clear();
                if (!collectObject(parser, path, config, result)) {
                    return false;
                }
            } else if (token == JsonToken.START_ARRAY) {
                containsContainer = true;
                scalarValues.clear();
                if (!collectDescendantArray(parser, path, config, result)) {
                    return false;
                }
            } else if (!containsContainer) {
                scalarValues.add(scalarValue(parser, token));
                if (!fitsCandidate(result, path, scalarValues, config.maxPreviewBytes())) {
                    return false;
                }
            }
        }
        if (token == null) {
            throw new IOException("Unexpected end of JSON array");
        }
        if (!containsContainer) {
            insert(result, path, new ArrayList<>(scalarValues));
        }
        return true;
    }

    private static boolean collectDescendants(
            JsonParser parser, JsonToken token, String path, PreviewConfig config, Map<String, Object> result)
            throws IOException {
        if (token == JsonToken.START_OBJECT) {
            return collectObject(parser, path, config, result);
        }
        if (token == JsonToken.START_ARRAY) {
            return collectDescendantArray(parser, path, config, result);
        }
        return true;
    }

    private static boolean collectDescendantArray(
            JsonParser parser, String path, PreviewConfig config, Map<String, Object> result) throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
            if (token == JsonToken.START_OBJECT) {
                if (!collectObject(parser, path, config, result)) {
                    return false;
                }
            } else if (token == JsonToken.START_ARRAY && !collectDescendantArray(parser, path, config, result)) {
                return false;
            }
        }
        if (token == null) {
            throw new IOException("Unexpected end of JSON array");
        }
        return true;
    }

    private static Object scalarValue(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getNumberValue();
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IOException("Unsupported JSON scalar token: " + token);
        };
    }

    private static boolean collectTree(
            JsonNode node, String pathPrefix, PreviewConfig config, Map<String, Object> result) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isArray()) {
            for (var item : node) {
                if (!collectTree(item, pathPrefix, config, result)) {
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
                if (!excluded && !collectTree(field.getValue(), path, config, result)) {
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
                if (!collectTree(field.getValue(), path, config, result)) {
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
        if (value instanceof String stringValue && utf8LengthExceeds(stringValue, maxPreviewBytes)) {
            return false;
        }
        if (!fitsCandidate(result, path, value, maxPreviewBytes)) {
            return false;
        }
        var converted = value instanceof JsonNode node ? MAPPER.convertValue(node, Object.class) : value;
        insert(result, path, converted);
        return true;
    }

    private static boolean fitsCandidate(Map<String, Object> result, String path, Object value, int maxPreviewBytes) {
        var candidate = copy(result);
        insert(candidate, path, value);
        return serializedSize(candidate) <= maxPreviewBytes;
    }

    private static boolean utf8LengthExceeds(String value, int limit) {
        var length = 0;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            final int bytes;
            if (character <= 0x7F) {
                bytes = 1;
            } else if (character <= 0x7FF) {
                bytes = 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes = 4;
                index++;
            } else if (Character.isSurrogate(character)) {
                bytes = 1;
            } else {
                bytes = 3;
            }
            if (bytes > limit - length) {
                return true;
            }
            length += bytes;
        }
        return false;
    }

    private record StreamingPreview(Map<String, Object> result, boolean fullyConsumed) {}

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
