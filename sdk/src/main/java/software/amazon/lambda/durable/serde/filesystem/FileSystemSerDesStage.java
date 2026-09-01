// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.serde.ComposableBinarySerDesStage;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesStage;
import software.amazon.lambda.durable.serde.Utf8StringBinaryCodec;

/**
 * A string stage that stores payloads on a durable shared filesystem.
 *
 * <p>Do not use Lambda's ephemeral {@code /tmp} storage. Use a durable shared mount such as EFS, or S3 Files only when
 * its synchronization and crash-durability tradeoffs are acceptable for the workload.
 *
 * <p>Payload files are immutable and created with a single {@code CREATE_NEW} write. Publication does not require hard
 * links or renames, so the write path is compatible with S3 Files.
 *
 * <p>The configured base path and all of its ancestors must already exist. Payload files are direct children of that
 * base path, so the stage never creates directories by pathname after opening a secure directory handle.
 *
 * <p>The mounted filesystem provider must support {@link SecureDirectoryStream}. The stage traverses to the
 * pre-provisioned base path with symbolic-link following disabled and keeps that handle open through file I/O,
 * preventing a checked path from being redirected between validation and access.
 *
 * <p>Every filesystem envelope includes a SHA-256 payload digest. Deserialization verifies inline values and file
 * contents against that digest, and file paths must contain the same digest.
 *
 * <p>Deserialization recognizes the reserved filesystem envelope marker. Input without that marker is returned
 * unchanged; input with the marker must be a valid supported envelope. Filesystem operations use the explicit
 * {@link SerDesContext} stage parameter for durable payload identity.
 */
public final class FileSystemSerDesStage implements SerDesStage {
    private static final String ENVELOPE_MARKER = "__durable_execution_filesystem_serdes";
    private static final String PAYLOAD_DIGEST_FIELD = "payloadDigest";
    private static final String PAYLOAD_TYPE_FIELD = "payloadType";
    private static final int ENVELOPE_VERSION = 1;
    private static final int DEFAULT_CHECKPOINT_ENVELOPE_LIMIT_BYTES = 256 * 1024 - 1024;
    private static final int MAX_URI_OWNER_PREFIX_LENGTH = 32;
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();
    private static final ObjectReader ENVELOPE_READER = ENVELOPE_MAPPER
            .reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Pattern SHA_256_DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Path basePath;
    private final FileSystemStorageMode storageMode;
    private final FileSystemPathEncoding pathEncoding;
    private final int checkpointEnvelopeLimitBytes;
    private final BiFunction<String, SerDesContext, Map<String, Object>> previewGenerator;

    private FileSystemSerDesStage(Builder builder) {
        basePath = builder.basePath.toAbsolutePath().normalize();
        storageMode = builder.storageMode;
        pathEncoding = builder.pathEncoding;
        checkpointEnvelopeLimitBytes = builder.checkpointEnvelopeLimitBytes;
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
    public String serialize(String value, SerDesContext context) {
        if (value == null) {
            return null;
        }
        context = requireContext(context);
        var payload = SerializedPayload.fromString(value);
        var payloadDigest = sha256(payload.data());
        if (storageMode == FileSystemStorageMode.OVERFLOW) {
            var inlineEnvelope = createEnvelope(payload.type(), payloadDigest, context);
            inlineEnvelope.put("data", value);
            if (fitsCheckpoint(inlineEnvelope, context)) {
                return encodeEnvelope(inlineEnvelope, context);
            }
        }

        var file = resolvePayloadPath(payloadDigest, context);
        var preview = generatePreview(value, context);
        var fileEnvelope = createEnvelope(payload.type(), payloadDigest, context);
        fileEnvelope.put("file", file.toString());
        if (preview != null) {
            fileEnvelope.put("preview", preview);
        }
        var encodedFileEnvelope = encodeEnvelope(fileEnvelope, context);
        if (!fitsCheckpoint(encodedFileEnvelope)) {
            throw new SerDesException("Filesystem SerDes envelope exceeds the checkpoint payload limit for entity '"
                    + context.entityId()
                    + "'");
        }
        try {
            writePayload(payload, file);
            return encodedFileEnvelope;
        } catch (IOException e) {
            throw new RetryableSerDesException(
                    "Failed to store filesystem payload for entity '" + context.entityId() + "'", e);
        }
    }

    @Override
    public String deserialize(String data, SerDesContext context) {
        if (data == null) {
            return null;
        }
        return resolveSerializedPayload(data, context);
    }

    private String resolveSerializedPayload(String data, SerDesContext context) {
        final boolean hasMarker;
        try {
            hasMarker = hasTopLevelFilesystemMarker(data);
        } catch (IOException e) {
            if (containsFilesystemMarkerField(data)) {
                throw malformedEnvelope(requireContext(context), e);
            }
            return data;
        }
        if (!hasMarker) {
            return data;
        }

        final JsonNode envelope;
        try {
            envelope = ENVELOPE_READER.readTree(data);
        } catch (JsonProcessingException e) {
            if (containsFilesystemMarkerField(data)) {
                throw malformedEnvelope(requireContext(context), e);
            }
            return data;
        }

        if (!hasFilesystemMarker(envelope)) {
            return data;
        }
        context = requireContext(context);
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
        var payloadDigest = payloadDigest(envelope, context);
        var owner = payloadOwner(envelope, context);
        if (hasData) {
            try {
                var payload = SerializedPayload.fromInlineValue(
                        payloadType, envelope.get("data").textValue());
                verifyPayloadDigest(payload, payloadDigest, context);
                return payload.value();
            } catch (IllegalArgumentException e) {
                throw malformedEnvelope(context, e);
            }
        }
        return readPayload(envelope.get("file").textValue(), payloadType, payloadDigest, owner, context)
                .value();
    }

    private static boolean hasTopLevelFilesystemMarker(String data) throws IOException {
        try (var parser = ENVELOPE_MAPPER.createParser(data)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.END_OBJECT) {
                if (token != JsonToken.FIELD_NAME) {
                    parser.skipChildren();
                    continue;
                }
                var fieldName = parser.currentName();
                var valueToken = parser.nextToken();
                if (ENVELOPE_MARKER.equals(fieldName)) {
                    return true;
                }
                if (valueToken != null) {
                    parser.skipChildren();
                }
            }
            return false;
        }
    }

    private SerializedPayload readPayload(
            String fileValue,
            PayloadType payloadType,
            String payloadDigest,
            PayloadOwner owner,
            SerDesContext context) {
        var file = basePath.getFileSystem().getPath(fileValue);
        if (!file.isAbsolute()) {
            throw new SerDesException("Filesystem SerDes file path must be absolute");
        }
        file = file.normalize();
        validatePayloadPath(file);
        var expectedFileName = payloadFileName(payloadDigest, owner.durableExecutionArn(), owner.entityId());
        if (!matchesPublishedPayloadFileName(file.getFileName().toString(), expectedFileName)) {
            throw new SerDesException(
                    "Filesystem SerDes file path does not match its declared owner and payload digest");
        }
        try {
            byte[] storedData;
            try (var directory = openSecureDirectory(file.getParent());
                    var channel = directory
                            .directory()
                            .newByteChannel(
                                    file.getFileName(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    var input = Channels.newInputStream(channel)) {
                storedData = input.readAllBytes();
            }
            var serialized = new SerializedPayload(payloadType, storedData);
            verifyPayloadDigest(serialized, payloadDigest, context);
            return serialized;
        } catch (IOException e) {
            throw new RetryableSerDesException(
                    "Failed to load filesystem payload for entity '" + context.entityId() + "'", e);
        }
    }

    private void validatePayloadPath(Path file) {
        var fileName = file.getFileName();
        if (fileName == null || file.getParent() == null || !file.getParent().equals(basePath)) {
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

    private static String payloadDigest(JsonNode envelope, SerDesContext context) {
        var node = envelope.get(PAYLOAD_DIGEST_FIELD);
        if (node == null
                || !node.isTextual()
                || !SHA_256_DIGEST_PATTERN.matcher(node.textValue()).matches()) {
            throw malformedEnvelope(context, null);
        }
        return node.textValue();
    }

    private static void verifyPayloadDigest(SerializedPayload payload, String expectedDigest, SerDesContext context) {
        if (!sha256(payload.data()).equals(expectedDigest)) {
            throw new SerDesException("Filesystem SerDes payload digest does not match stored content for entity '"
                    + context.entityId()
                    + "'");
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
                || !isPayloadType(envelope.get(PAYLOAD_TYPE_FIELD).textValue())
                || !envelope.has(PAYLOAD_DIGEST_FIELD)
                || !envelope.get(PAYLOAD_DIGEST_FIELD).isTextual()
                || !SHA_256_DIGEST_PATTERN
                        .matcher(envelope.get(PAYLOAD_DIGEST_FIELD).textValue())
                        .matches()) {
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
        return envelope.size() == (hasPreview ? 7 : 6);
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

    private static boolean containsFilesystemMarkerField(String data) {
        var index = 0;
        while (index < data.length() && Character.isWhitespace(data.charAt(index))) {
            index++;
        }
        if (index == data.length() || data.charAt(index) != '{') {
            return false;
        }

        var containerDepth = 1;
        for (index++; index < data.length() && containerDepth > 0; index++) {
            var current = data.charAt(index);
            if (current == '{' || current == '[') {
                containerDepth++;
            } else if (current == '}' || current == ']') {
                containerDepth--;
            } else if (current == '"') {
                var literalStart = index;
                var valueStart = index + 1;
                var escaped = false;
                while (++index < data.length()) {
                    current = data.charAt(index);
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == '"') {
                        break;
                    }
                }
                if (index == data.length()) {
                    return false;
                }

                var delimiter = index + 1;
                while (delimiter < data.length() && Character.isWhitespace(data.charAt(delimiter))) {
                    delimiter++;
                }
                if (containerDepth == 1
                        && delimiter < data.length()
                        && data.charAt(delimiter) == ':'
                        && isFilesystemMarkerLiteral(data, literalStart, valueStart, index)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isFilesystemMarkerLiteral(String data, int literalStart, int valueStart, int literalEnd) {
        if (literalEnd - valueStart == ENVELOPE_MARKER.length()
                && data.regionMatches(valueStart, ENVELOPE_MARKER, 0, ENVELOPE_MARKER.length())) {
            return true;
        }
        try {
            return ENVELOPE_MARKER.equals(
                    ENVELOPE_MAPPER.readValue(data.substring(literalStart, literalEnd + 1), String.class));
        } catch (JsonProcessingException ignored) {
            return false;
        }
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

    private LinkedHashMap<String, Object> createEnvelope(
            PayloadType payloadType, String payloadDigest, SerDesContext context) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put(ENVELOPE_MARKER, ENVELOPE_VERSION);
        envelope.put("ownerDurableExecutionArn", context.durableExecutionArn());
        envelope.put("ownerEntityId", context.entityId());
        envelope.put(PAYLOAD_TYPE_FIELD, payloadType.name());
        envelope.put(PAYLOAD_DIGEST_FIELD, payloadDigest);
        return envelope;
    }

    private String encodeEnvelope(Map<String, Object> envelope, SerDesContext context) {
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
            return previewGenerator.apply(value, context);
        } catch (RetryableSerDesException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SerDesException(
                    "Failed to generate filesystem payload preview for entity '" + context.entityId() + "'", e);
        }
    }

    private boolean fitsCheckpoint(Map<String, Object> envelope, SerDesContext context) {
        var counter = new CappedCountingOutputStream(checkpointEnvelopeLimitBytes);
        try {
            ENVELOPE_MAPPER.writeValue(counter, envelope);
            return !counter.exceeded();
        } catch (IOException e) {
            throw new SerDesException(
                    "Failed to measure filesystem payload envelope for entity '" + context.entityId() + "'", e);
        }
    }

    private boolean fitsCheckpoint(String encodedEnvelope) {
        return encodedEnvelope.getBytes(StandardCharsets.UTF_8).length <= checkpointEnvelopeLimitBytes;
    }

    private SerDesContext requireContext(SerDesContext context) {
        if (context == null
                || context.durableExecutionArn() == null
                || context.durableExecutionArn().isBlank()
                || context.entityId() == null
                || context.entityId().isBlank()) {
            throw new SerDesException(
                    "FileSystemSerDesStage requires an SDK-managed SerDesContext with durableExecutionArn and entityId");
        }
        return context;
    }

    private Path resolvePayloadPath(String payloadDigest, SerDesContext context) {
        var deterministicName = payloadFileName(payloadDigest, context.durableExecutionArn(), context.entityId());
        var suffix = ".payload";
        var fileName = deterministicName.substring(0, deterministicName.length() - suffix.length())
                + "-"
                + UUID.randomUUID()
                + suffix;
        var file = basePath.resolve(fileName).normalize();
        if (!basePath.equals(file.getParent())) {
            throw new SerDesException("Resolved filesystem payload path is outside the configured base path");
        }
        return file;
    }

    private String payloadFileName(String payloadDigest, String durableExecutionArn, String entityId) {
        return payloadOwnerPrefix(durableExecutionArn, entityId) + "-" + payloadDigest + ".payload";
    }

    private void writePayload(SerializedPayload payload, Path file) throws IOException {
        var directory = file.getParent();
        try (var secureDirectory = openSecureDirectory(directory)) {
            var created = false;
            try (var channel = secureDirectory
                    .directory()
                    .newByteChannel(
                            file.getFileName(),
                            Set.of(
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS))) {
                created = true;
                var buffer = ByteBuffer.wrap(payload.data());
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            } catch (FileAlreadyExistsException failure) {
                throw failure;
            } catch (IOException failure) {
                if (created) {
                    try {
                        secureDirectory.directory().deleteFile(file.getFileName());
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
    }

    private SecureDirectoryHandle openSecureDirectory(Path directory) throws IOException {
        if (directory == null || !directory.startsWith(basePath)) {
            throw new SerDesException("Filesystem SerDes directory is outside the configured base path");
        }
        var root = basePath.getRoot();
        if (root == null) {
            throw new SerDesException("Filesystem SerDes base path must be absolute");
        }

        var openedStreams = new ArrayList<DirectoryStream<Path>>();
        try {
            var current = requireSecureDirectoryStream(Files.newDirectoryStream(root), openedStreams);
            for (var component : root.relativize(directory)) {
                try {
                    current = requireSecureDirectoryStream(
                            current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS), openedStreams);
                } catch (NoSuchFileException missing) {
                    throw new SerDesException(
                            "Filesystem SerDes base path and all ancestors must already exist", missing);
                }
            }
            return new SecureDirectoryHandle(current, openedStreams);
        } catch (IOException | RuntimeException failure) {
            closeDirectoryStreams(openedStreams, failure);
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> requireSecureDirectoryStream(
            DirectoryStream<Path> stream, List<DirectoryStream<Path>> openedStreams) {
        openedStreams.add(stream);
        if (stream instanceof SecureDirectoryStream<?> secureStream) {
            return (SecureDirectoryStream<Path>) secureStream;
        }
        throw new SerDesException(
                "FileSystemSerDesStage requires a filesystem provider with SecureDirectoryStream support");
    }

    private static void closeDirectoryStreams(List<DirectoryStream<Path>> streams, Throwable failure) {
        for (int index = streams.size() - 1; index >= 0; index--) {
            try {
                streams.get(index).close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
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

    private String payloadOwnerPrefix(String durableExecutionArn, String entityId) {
        var ownerDigest = sha256(durableExecutionArn + "\0" + entityId);
        if (pathEncoding == FileSystemPathEncoding.HASH) {
            return ownerDigest;
        }
        var readableEntity = encode(entityId);
        var readablePrefix =
                readableEntity.substring(0, Math.min(readableEntity.length(), MAX_URI_OWNER_PREFIX_LENGTH));
        return readablePrefix + "-" + ownerDigest;
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

    private static final class SecureDirectoryHandle implements AutoCloseable {
        private final SecureDirectoryStream<Path> directory;
        private final List<DirectoryStream<Path>> openedStreams;

        private SecureDirectoryHandle(
                SecureDirectoryStream<Path> directory, List<DirectoryStream<Path>> openedStreams) {
            this.directory = directory;
            this.openedStreams = List.copyOf(openedStreams);
        }

        private SecureDirectoryStream<Path> directory() {
            return directory;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int index = openedStreams.size() - 1; index >= 0; index--) {
                try {
                    openedStreams.get(index).close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private enum PayloadType {
        STRING
    }

    private record SerializedPayload(PayloadType type, byte[] data) {
        private SerializedPayload {
            Objects.requireNonNull(type, "type cannot be null");
        }

        private static SerializedPayload fromString(String value) {
            return new SerializedPayload(PayloadType.STRING, Utf8StringBinaryCodec.INSTANCE.toBytes(value));
        }

        private static SerializedPayload fromInlineValue(PayloadType type, String value) {
            return fromString(value);
        }

        private String value() {
            if (data == null) {
                throw new IllegalStateException("Serialized payload does not contain data");
            }
            return Utf8StringBinaryCodec.INSTANCE.fromBytes(data);
        }
    }

    private static final class CappedCountingOutputStream extends OutputStream {
        private final long limit;
        private long count;

        private CappedCountingOutputStream(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) {
            add(1);
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            add(length);
        }

        private void add(long length) {
            count = Math.min(limit + 1, count + length);
        }

        private boolean exceeded() {
            return count > limit;
        }
    }

    /** Builder for {@link FileSystemSerDesStage}. */
    public static final class Builder {
        private final Path basePath;
        private FileSystemStorageMode storageMode = FileSystemStorageMode.ALWAYS;
        private FileSystemPathEncoding pathEncoding = FileSystemPathEncoding.URI;
        private int checkpointEnvelopeLimitBytes = DEFAULT_CHECKPOINT_ENVELOPE_LIMIT_BYTES;
        private BiFunction<String, SerDesContext, Map<String, Object>> previewGenerator;

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

        /**
         * Configures the maximum UTF-8 size of an inline or file checkpoint envelope.
         *
         * <p>{@link FileSystemStorageMode#OVERFLOW} offloads an inline envelope that exceeds this limit. A final file
         * envelope that exceeds the limit is rejected. The configured value should not exceed the payload limit
         * accepted by the durable execution service. The default is 255 KiB.
         *
         * @param checkpointEnvelopeLimitBytes positive envelope limit in bytes
         * @return this builder
         * @throws IllegalArgumentException if {@code checkpointEnvelopeLimitBytes} is not positive
         */
        public Builder checkpointEnvelopeLimitBytes(int checkpointEnvelopeLimitBytes) {
            if (checkpointEnvelopeLimitBytes <= 0) {
                throw new IllegalArgumentException("checkpointEnvelopeLimitBytes must be positive");
            }
            this.checkpointEnvelopeLimitBytes = checkpointEnvelopeLimitBytes;
            return this;
        }

        /**
         * Configures a custom preview generator that receives the string produced by the preceding pipeline stage and
         * its serialization context.
         *
         * <p>{@link SerDesContext#originalValue()} contains the object supplied to the pipeline's root value codec. The
         * returned preview is included only when the payload is stored in a file. Preview generators are responsible
         * for avoiding disclosure of sensitive fields from either input.
         */
        public Builder previewGenerator(BiFunction<String, SerDesContext, Map<String, Object>> previewGenerator) {
            this.previewGenerator = Objects.requireNonNull(previewGenerator, "previewGenerator cannot be null");
            return this;
        }

        /**
         * Configures structured preview generation for JSON produced by the preceding stage.
         *
         * <p>Use {@link #previewGenerator(BiFunction)} for non-JSON stage values or fully custom preview logic.
         */
        public Builder previewConfig(PreviewConfig previewConfig) {
            Objects.requireNonNull(previewConfig, "previewConfig cannot be null");
            this.previewGenerator = (value, context) -> SerDesPreview.buildPreviewFromJson(value, previewConfig);
            return this;
        }

        public FileSystemSerDesStage build() {
            return new FileSystemSerDesStage(this);
        }
    }
}
