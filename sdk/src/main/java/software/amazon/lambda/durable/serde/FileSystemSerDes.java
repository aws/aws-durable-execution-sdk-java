// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * A string stage that stores payloads on a durable shared filesystem.
 *
 * <p>Do not use Lambda's ephemeral {@code /tmp} storage. Use a durable shared mount such as EFS, or S3 Files only when
 * its synchronization and crash-durability tradeoffs are acceptable for the workload.
 *
 * <p>Deserialization recognizes the reserved filesystem envelope marker. Input without that marker is returned
 * unchanged; input with the marker must be a valid supported envelope.
 */
public final class FileSystemSerDes implements SerDesStage {
    private static final String ENVELOPE_MARKER = "__durable_execution_filesystem_serdes";
    private static final String ENVELOPE_PREFIX = "{\"" + ENVELOPE_MARKER + "\":";
    private static final String PAYLOAD_TYPE_FIELD = "payloadType";
    private static final int ENVELOPE_VERSION = 1;
    private static final int CHECKPOINT_ENVELOPE_LIMIT_BYTES = 256 * 1024 - 1024;
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();
    private static final Pattern DURABLE_EXECUTION_ARN_PATTERN = Pattern.compile(
            "^arn:[^:]*:lambda:[^:]*:[^:]*:function:([^:/]+):[^:/]+/durable-execution/([^/]+)/([^/]+)$");

    private final Path basePath;
    private final FileSystemStorageMode storageMode;
    private final FileSystemPathEncoding pathEncoding;
    private final Function<String, Map<String, Object>> previewGenerator;
    private volatile Path canonicalBasePath;

    private FileSystemSerDes(Builder builder) {
        basePath = builder.basePath.toAbsolutePath().normalize();
        storageMode = builder.storageMode;
        pathEncoding = builder.pathEncoding;
        previewGenerator = builder.previewGenerator;
    }

    /**
     * Creates a filesystem stage builder for use after a value codec in a composable SerDes pipeline.
     *
     * <p>Use {@link ComposableBinarySerDesStage} before this stage when binary transformations are required.
     *
     * @param basePath durable shared filesystem root
     * @return a filesystem stage builder
     */
    public static Builder builder(Path basePath) {
        return new Builder(basePath);
    }

    @Override
    public String serialize(String value) {
        if (value == null) {
            return null;
        }
        var context = requireContext();
        var payload = SerializedPayload.fromString(value);
        if (storageMode == FileSystemStorageMode.OVERFLOW) {
            var inlineEnvelope = encodeEnvelope(payload, null, null, context);
            if (fitsCheckpoint(inlineEnvelope)) {
                return inlineEnvelope;
            }
        }

        var file = resolvePayloadPath(payload, context);
        var preview = generatePreview(value, context);
        var fileEnvelope = encodeEnvelope(payload.withoutData(), file, preview, context);
        if (!fitsCheckpoint(fileEnvelope)) {
            throw new SerDesException("Filesystem SerDes envelope exceeds the checkpoint payload limit for entity '"
                    + context.entityId()
                    + "'");
        }
        try {
            var publishedFile = writePayload(payload, file);
            if (publishedFile.equals(file)) {
                return fileEnvelope;
            }
            var fallbackEnvelope = encodeEnvelope(payload.withoutData(), publishedFile, preview, context);
            if (!fitsCheckpoint(fallbackEnvelope)) {
                throw new SerDesException("Filesystem SerDes envelope exceeds the checkpoint payload limit for entity '"
                        + context.entityId()
                        + "'");
            }
            return fallbackEnvelope;
        } catch (IOException e) {
            throw new RetryableSerDesException(
                    "Failed to store filesystem payload for entity '" + context.entityId() + "'", e);
        }
    }

    @Override
    public String deserialize(String data) {
        if (data == null) {
            return null;
        }
        return resolveSerializedPayload(data);
    }

    private String resolveSerializedPayload(String data) {
        final JsonNode envelope;
        try {
            envelope = ENVELOPE_MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            if (data.startsWith(ENVELOPE_PREFIX)) {
                throw malformedEnvelope(requireContext(), e);
            }
            return data;
        }

        if (!hasFilesystemMarker(envelope)) {
            return data;
        }
        var context = requireContext();
        var marker = envelope.get(ENVELOPE_MARKER);
        if (!marker.isIntegralNumber()) {
            throw malformedEnvelope(context, null);
        }
        if (!marker.canConvertToInt() || marker.intValue() != ENVELOPE_VERSION) {
            throw unsupportedEnvelopeVersion(context, marker.asText());
        }
        if (!isFilesystemEnvelope(envelope)) {
            throw malformedEnvelope(context, null);
        }

        var hasData = envelope.has("data") && envelope.get("data").isTextual();
        var hasFile = envelope.has("file") && envelope.get("file").isTextual();
        var payloadType = payloadType(envelope, context);
        var owner = payloadOwner(envelope, context);
        if (hasData) {
            try {
                return SerializedPayload.fromInlineValue(
                                payloadType, envelope.get("data").textValue())
                        .value();
            } catch (IllegalArgumentException e) {
                throw malformedEnvelope(context, e);
            }
        }
        return readPayload(envelope.get("file").textValue(), payloadType, owner, context)
                .value();
    }

    private SerializedPayload readPayload(
            String fileValue, PayloadType payloadType, PayloadOwner owner, SerDesContext context) {
        var file = basePath.getFileSystem().getPath(fileValue).toAbsolutePath().normalize();
        validatePayloadPath(file, owner);
        try {
            var realBasePath = validateBasePath(false);
            rejectSymbolicLinks(file);
            var realDirectory = file.getParent().toRealPath();
            var realFile = file.toRealPath();
            if (!realDirectory.startsWith(realBasePath)
                    || !realFile.getParent().equals(realDirectory)
                    || !realFile.equals(file.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new SerDesException("Filesystem SerDes file does not resolve to the expected payload path");
            }
            var serialized = new SerializedPayload(payloadType, Files.readAllBytes(realFile));
            var expectedFileName = payloadFileName(serialized, owner.entityId());
            if (!matchesPublishedPayloadFileName(realFile.getFileName().toString(), expectedFileName)) {
                throw new SerDesException("Filesystem SerDes file content does not match its content-addressed path");
            }
            return serialized;
        } catch (IOException e) {
            throw new RetryableSerDesException(
                    "Failed to load filesystem payload for entity '" + context.entityId() + "'", e);
        }
    }

    private void validatePayloadPath(Path file, PayloadOwner owner) {
        var expectedDirectory = resolveExecutionDirectory(owner.durableExecutionArn());
        var fileName = file.getFileName();
        if (fileName == null
                || file.getParent() == null
                || !file.getParent().equals(expectedDirectory)
                || !fileName.toString()
                        .matches(Pattern.quote(encode(owner.entityId()))
                                + "-[0-9a-f]{64}(?:-[A-Za-z0-9_-]+)?\\.payload")) {
            throw new SerDesException("Filesystem SerDes file is not valid for its declared durable entity");
        }
    }

    private static PayloadType payloadType(JsonNode envelope, SerDesContext context) {
        var node = envelope.get(PAYLOAD_TYPE_FIELD);
        if (node == null || !node.isTextual()) {
            throw malformedEnvelope(context, null);
        }
        try {
            return PayloadType.valueOf(node.textValue());
        } catch (IllegalArgumentException e) {
            throw malformedEnvelope(context, e);
        }
    }

    private static PayloadOwner payloadOwner(JsonNode envelope, SerDesContext context) {
        var hasOwnerArn = envelope.has("ownerDurableExecutionArn")
                && envelope.get("ownerDurableExecutionArn").isTextual();
        var hasOwnerEntity =
                envelope.has("ownerEntityId") && envelope.get("ownerEntityId").isTextual();
        if (!hasOwnerArn || !hasOwnerEntity) {
            throw malformedEnvelope(context, null);
        }

        var owner = new PayloadOwner(
                envelope.get("ownerDurableExecutionArn").textValue(),
                envelope.get("ownerEntityId").textValue());
        if (owner.durableExecutionArn().isBlank() || owner.entityId().isBlank()) {
            throw malformedEnvelope(context, null);
        }

        var sameOwner = owner.durableExecutionArn().equals(context.durableExecutionArn())
                && owner.entityId().equals(context.entityId());
        if (!sameOwner && !acceptsCrossExecutionReference(context)) {
            throw new SerDesException("Filesystem SerDes file belongs to a different durable entity");
        }
        return owner;
    }

    private static boolean acceptsCrossExecutionReference(SerDesContext context) {
        return context.payloadKind() == SerDesPayloadKind.INPUT
                || context.operationType() == OperationType.CHAINED_INVOKE;
    }

    private void rejectSymbolicLinks(Path file) throws IOException {
        validateBasePath(false);
        var current = basePath;
        for (var component : basePath.relativize(file)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new SerDesException("Filesystem SerDes payload path must not contain symbolic links");
            }
        }
    }

    private static boolean isFilesystemEnvelope(JsonNode envelope) {
        if (envelope == null
                || !envelope.isObject()
                || !envelope.has(ENVELOPE_MARKER)
                || !envelope.get(ENVELOPE_MARKER).isIntegralNumber()
                || !envelope.get(ENVELOPE_MARKER).canConvertToInt()
                || envelope.get(ENVELOPE_MARKER).intValue() != ENVELOPE_VERSION
                || !envelope.has("ownerDurableExecutionArn")
                || !envelope.get("ownerDurableExecutionArn").isTextual()
                || envelope.get("ownerDurableExecutionArn").textValue().isBlank()
                || !envelope.has("ownerEntityId")
                || !envelope.get("ownerEntityId").isTextual()
                || envelope.get("ownerEntityId").textValue().isBlank()
                || !envelope.has(PAYLOAD_TYPE_FIELD)
                || !envelope.get(PAYLOAD_TYPE_FIELD).isTextual()
                || !isPayloadType(envelope.get(PAYLOAD_TYPE_FIELD).textValue())) {
            return false;
        }

        var hasData = envelope.has("data") && envelope.get("data").isTextual();
        var hasFile = envelope.has("file") && envelope.get("file").isTextual();
        if (hasData == hasFile) {
            return false;
        }

        var hasPreview = envelope.has("preview");
        if (hasPreview && (hasData || !envelope.get("preview").isObject())) {
            return false;
        }
        return envelope.size() == (hasPreview ? 6 : 5);
    }

    private static boolean isPayloadType(String value) {
        try {
            PayloadType.valueOf(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean hasFilesystemMarker(JsonNode envelope) {
        return envelope != null && envelope.isObject() && envelope.has(ENVELOPE_MARKER);
    }

    private static SerDesException malformedEnvelope(SerDesContext context, Throwable cause) {
        var message = "Invalid filesystem SerDes envelope for entity '" + context.entityId() + "'";
        return cause == null ? new SerDesException(message) : new SerDesException(message, cause);
    }

    private static SerDesException unsupportedEnvelopeVersion(SerDesContext context, String version) {
        return new SerDesException("Unsupported filesystem SerDes envelope version "
                + version
                + " for entity '"
                + context.entityId()
                + "'");
    }

    private String encodeEnvelope(
            SerializedPayload payload, Path file, Map<String, Object> preview, SerDesContext context) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put(ENVELOPE_MARKER, ENVELOPE_VERSION);
        envelope.put("ownerDurableExecutionArn", context.durableExecutionArn());
        envelope.put("ownerEntityId", context.entityId());
        envelope.put(PAYLOAD_TYPE_FIELD, payload.type().name());
        if (payload.hasData()) {
            envelope.put("data", payload.inlineValue());
        } else {
            envelope.put("file", file.toString());
            if (preview != null) {
                envelope.put("preview", preview);
            }
        }
        try {
            return ENVELOPE_MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new SerDesException(
                    "Failed to encode filesystem payload envelope for entity '" + context.entityId() + "'", e);
        }
    }

    private Map<String, Object> generatePreview(String value, SerDesContext context) {
        if (previewGenerator == null) {
            return null;
        }
        try {
            return previewGenerator.apply(value);
        } catch (RuntimeException e) {
            throw new SerDesException(
                    "Failed to generate filesystem payload preview for entity '" + context.entityId() + "'", e);
        }
    }

    private static boolean fitsCheckpoint(String envelope) {
        return Utf8StringBinaryCodec.INSTANCE.toBytes(envelope).length <= CHECKPOINT_ENVELOPE_LIMIT_BYTES;
    }

    private SerDesContext requireContext() {
        var context = SerDesContext.getCurrentContext();
        if (context == null
                || context.durableExecutionArn() == null
                || context.durableExecutionArn().isBlank()
                || context.entityId() == null
                || context.entityId().isBlank()) {
            throw new SerDesException(
                    "FileSystemSerDes requires an SDK-managed SerDesContext with durableExecutionArn and entityId");
        }
        return context;
    }

    private Path resolvePayloadPath(SerializedPayload payload, SerDesContext context) {
        var directory = resolveExecutionDirectory(context.durableExecutionArn());
        var fileName = payloadFileName(payload, context.entityId());
        var file = directory.resolve(fileName).normalize();
        if (!file.startsWith(directory)) {
            throw new SerDesException("Resolved filesystem payload path is outside the execution directory");
        }
        return file;
    }

    private String payloadFileName(SerializedPayload payload, String entityId) {
        return encode(entityId) + "-" + sha256(payload.data()) + ".payload";
    }

    private Path writePayload(SerializedPayload payload, Path file) throws IOException {
        var directory = file.getParent();
        var realBasePath = createDirectoriesWithoutSymbolicLinks(directory);
        rejectSymbolicLinks(file);
        var realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(realBasePath)) {
            throw new SerDesException("Filesystem SerDes directory resolves outside the configured base path");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            validateExistingPayload(file, payload.data());
            return file;
        }

        var fileName = file.getFileName().toString();
        var temporary = Files.createTempFile(
                directory, fileName.substring(0, fileName.length() - ".payload".length()) + "-", ".payload");
        var retainTemporary = false;
        try {
            Files.write(temporary, payload.data());
            var publishedFile = publishWithoutReplacement(temporary, file, payload.data());
            retainTemporary = publishedFile.equals(temporary);
            return publishedFile;
        } finally {
            if (!retainTemporary) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private Path createDirectoriesWithoutSymbolicLinks(Path directory) throws IOException {
        var realBasePath = validateBasePath(true);
        var current = basePath;
        for (var component : basePath.relativize(directory)) {
            current = current.resolve(component);
            ensureRealDirectory(current, true);
        }
        return realBasePath;
    }

    private Path validateBasePath(boolean createMissing) throws IOException {
        var current = basePath.getRoot();
        if (current == null) {
            throw new SerDesException("Filesystem SerDes base path must be absolute");
        }
        ensureRealDirectory(current, false);
        for (var component : basePath) {
            current = current.resolve(component);
            ensureRealDirectory(current, createMissing);
        }
        return retainCanonicalBasePath(basePath.toRealPath());
    }

    private static void ensureRealDirectory(Path directory, boolean createMissing) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!createMissing) {
                throw new NoSuchFileException(directory.toString());
            }
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException ignored) {
                // Validate the entry created by another writer below.
            }
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new SerDesException("Filesystem SerDes directory path must contain only real directories");
        }
    }

    private synchronized Path retainCanonicalBasePath(Path currentBasePath) {
        if (canonicalBasePath == null) {
            canonicalBasePath = currentBasePath;
        } else if (!canonicalBasePath.equals(currentBasePath)) {
            throw new SerDesException("Filesystem SerDes base path changed after validation");
        }
        return canonicalBasePath;
    }

    private Path publishWithoutReplacement(Path temporary, Path file, byte[] expectedData) throws IOException {
        try {
            Files.createLink(file, temporary);
            return file;
        } catch (FileAlreadyExistsException ignored) {
            validateExistingPayload(file, expectedData);
            return file;
        } catch (UnsupportedOperationException | IOException ignored) {
            validateExistingPayload(temporary, expectedData);
            return temporary;
        }
    }

    private static boolean matchesPublishedPayloadFileName(String actualFileName, String expectedFileName) {
        if (actualFileName.equals(expectedFileName)) {
            return true;
        }
        var suffix = ".payload";
        var expectedPrefix = expectedFileName.substring(0, expectedFileName.length() - suffix.length());
        return actualFileName.startsWith(expectedPrefix + "-") && actualFileName.endsWith(suffix);
    }

    private void validateExistingPayload(Path file, byte[] expectedData) throws IOException {
        rejectSymbolicLinks(file);
        var existing = Files.readAllBytes(file);
        if (!Arrays.equals(existing, expectedData)) {
            throw new SerDesException("Filesystem SerDes content-addressed file contains unexpected data");
        }
    }

    private Path resolveExecutionDirectory(String durableExecutionArn) {
        Path directory;
        if (pathEncoding == FileSystemPathEncoding.URI) {
            var matcher = DURABLE_EXECUTION_ARN_PATTERN.matcher(durableExecutionArn);
            if (matcher.matches()) {
                directory = basePath.resolve(encode(matcher.group(1)))
                        .resolve(encode(matcher.group(2)))
                        .resolve(encode(matcher.group(3)))
                        .normalize();
                if (!directory.startsWith(basePath)) {
                    throw new SerDesException("Resolved filesystem execution path is outside the configured base path");
                }
                return directory;
            }
        }
        directory = basePath.resolve(encode(durableExecutionArn)).normalize();
        if (!directory.startsWith(basePath)) {
            throw new SerDesException("Resolved filesystem execution path is outside the configured base path");
        }
        return directory;
    }

    private String encode(String value) {
        if (pathEncoding == FileSystemPathEncoding.HASH) {
            return sha256(value);
        }
        var encoded = new StringBuilder();
        for (byte valueByte : Utf8StringBinaryCodec.INSTANCE.toBytes(value)) {
            int current = valueByte & 0xff;
            if (current >= 'a' && current <= 'z'
                    || current >= 'A' && current <= 'Z'
                    || current >= '0' && current <= '9'
                    || current == '-'
                    || current == '_'
                    || current == '.'
                    || current == '~') {
                encoded.append((char) current);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(current >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(current & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static String sha256(String value) {
        return sha256(Utf8StringBinaryCodec.INSTANCE.toBytes(value));
    }

    private static String sha256(byte[] value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record PayloadOwner(String durableExecutionArn, String entityId) {}

    private enum PayloadType {
        STRING
    }

    private record SerializedPayload(PayloadType type, byte[] data) {
        private SerializedPayload {
            Objects.requireNonNull(type, "type cannot be null");
            data = data == null ? null : data.clone();
        }

        private static SerializedPayload fromString(String value) {
            return new SerializedPayload(PayloadType.STRING, Utf8StringBinaryCodec.INSTANCE.toBytes(value));
        }

        private static SerializedPayload fromInlineValue(PayloadType type, String value) {
            return fromString(value);
        }

        @Override
        public byte[] data() {
            return data == null ? null : data.clone();
        }

        private boolean hasData() {
            return data != null;
        }

        private SerializedPayload withoutData() {
            return new SerializedPayload(type, null);
        }

        private String inlineValue() {
            if (data == null) {
                throw new IllegalStateException("Serialized payload does not contain inline data");
            }
            return Utf8StringBinaryCodec.INSTANCE.fromBytes(data);
        }

        private String value() {
            if (data == null) {
                throw new IllegalStateException("Serialized payload does not contain data");
            }
            return Utf8StringBinaryCodec.INSTANCE.fromBytes(data);
        }
    }

    /** Builder for {@link FileSystemSerDes}. */
    public static final class Builder {
        private final Path basePath;
        private FileSystemStorageMode storageMode = FileSystemStorageMode.ALWAYS;
        private FileSystemPathEncoding pathEncoding = FileSystemPathEncoding.URI;
        private Function<String, Map<String, Object>> previewGenerator;

        private Builder(Path basePath) {
            this.basePath = Objects.requireNonNull(basePath, "basePath cannot be null");
        }

        public Builder storageMode(FileSystemStorageMode storageMode) {
            this.storageMode = Objects.requireNonNull(storageMode, "storageMode cannot be null");
            return this;
        }

        public Builder pathEncoding(FileSystemPathEncoding pathEncoding) {
            this.pathEncoding = Objects.requireNonNull(pathEncoding, "pathEncoding cannot be null");
            return this;
        }

        public Builder previewGenerator(Function<String, Map<String, Object>> previewGenerator) {
            this.previewGenerator = Objects.requireNonNull(previewGenerator, "previewGenerator cannot be null");
            return this;
        }

        public FileSystemSerDes build() {
            return new FileSystemSerDes(this);
        }
    }
}
