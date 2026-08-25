// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * A SerDes that stores payloads on a durable shared filesystem.
 *
 * <p>Use {@link #stageBuilder(Path)} when composing this implementation after a value codec. The compatibility
 * {@link #builder(Path)} form includes its own value codec and can be used as a standalone SerDes.
 *
 * <p>Do not use Lambda's ephemeral {@code /tmp} storage. Use a durable shared mount such as EFS, or S3 Files only when
 * its synchronization and crash-durability tradeoffs are acceptable for the workload.
 */
public final class FileSystemSerDes implements SerDes {
    private static final String ENVELOPE_MARKER = "__durable_execution_filesystem_serdes";
    private static final int ENVELOPE_VERSION = 1;
    private static final int CHECKPOINT_ENVELOPE_LIMIT_BYTES = 256 * 1024 - 1024;
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();
    private static final Pattern DURABLE_EXECUTION_ARN_PATTERN = Pattern.compile(
            "^arn:[^:]*:lambda:[^:]*:[^:]*:function:([^:/]+):[^:/]+/durable-execution/([^/]+)/([^/]+)$");

    private final Path basePath;
    private final FileSystemStorageMode storageMode;
    private final FileSystemPathEncoding pathEncoding;
    private final SerDes delegate;
    private final Function<Object, Map<String, Object>> previewGenerator;
    private final boolean stageMode;
    private volatile Path canonicalBasePath;

    private FileSystemSerDes(Builder builder) {
        basePath = builder.basePath.toAbsolutePath().normalize();
        storageMode = builder.storageMode;
        pathEncoding = builder.pathEncoding;
        delegate = builder.delegate;
        previewGenerator = builder.previewGenerator;
        stageMode = builder.stageMode;
    }

    /**
     * Creates a standalone filesystem SerDes builder with {@link JacksonSerDes} as its default value codec.
     *
     * @param basePath durable shared filesystem root
     * @return a standalone builder
     */
    public static Builder builder(Path basePath) {
        return new Builder(basePath, false);
    }

    /**
     * Creates a filesystem string-stage builder for use in a composable SerDes pipeline.
     *
     * @param basePath durable shared filesystem root
     * @return a string-stage builder
     */
    public static Builder stageBuilder(Path basePath) {
        return new Builder(basePath, true);
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        var context = requireContext();
        var serialized = serializeValue(value);
        if (storageMode == FileSystemStorageMode.OVERFLOW) {
            var inlineEnvelope = encodeEnvelope(serialized, null, null, context);
            if (fitsCheckpoint(inlineEnvelope)) {
                return inlineEnvelope;
            }
        }

        var file = resolvePayloadPath(serialized, context);
        var preview = generatePreview(value, context);
        var fileEnvelope = encodeEnvelope(null, file, preview, context);
        if (!fitsCheckpoint(fileEnvelope)) {
            throw new SerDesException("Filesystem SerDes envelope exceeds the checkpoint payload limit for entity '"
                    + context.entityId()
                    + "'");
        }
        try {
            writePayload(serialized, file);
            return fileEnvelope;
        } catch (IOException e) {
            throw new RetryableSerDesException(
                    "Failed to store filesystem payload for entity '" + context.entityId() + "'", e);
        }
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        var context = requireContext();
        var serialized = resolveSerializedPayload(data, context).serialized();
        if (stageMode) {
            if (!TypeToken.get(String.class).equals(typeToken)) {
                throw new SerDesException("FileSystemSerDes stage can only deserialize to String");
            }
            @SuppressWarnings("unchecked")
            var value = (T) serialized;
            return value;
        }
        return delegate.deserialize(serialized, typeToken);
    }

    @Override
    public SerDesStageResult deserializePipelineStage(String data) {
        if (!stageMode) {
            return SerDes.super.deserializePipelineStage(data);
        }
        var context = requireContext();
        var resolved = resolveSerializedPayload(data, context);
        return resolved.external()
                ? SerDesStageResult.decodeWithValueCodec(resolved.serialized())
                : SerDesStageResult.continueWith(resolved.serialized());
    }

    @Override
    public boolean requiresDurableContext() {
        return true;
    }

    @Override
    public boolean isTerminalPipelineStage() {
        return true;
    }

    private String serializeValue(Object value) {
        if (stageMode) {
            if (!(value instanceof String stringValue)) {
                throw new SerDesException("FileSystemSerDes stage can only serialize String values");
            }
            return stringValue;
        }
        var serialized = delegate.serialize(value);
        if (serialized == null) {
            throw new SerDesException("Delegate SerDes returned null for a non-null value");
        }
        return serialized;
    }

    private ResolvedPayload resolveSerializedPayload(String data, SerDesContext context) {
        final JsonNode envelope;
        try {
            envelope = ENVELOPE_MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            if (acceptsExternalPayload(context)) {
                return new ResolvedPayload(data, true);
            }
            throw malformedEnvelope(context, e);
        }

        if (envelope != null && envelope.isObject() && envelope.has(ENVELOPE_MARKER)) {
            if (!isFilesystemEnvelope(envelope)) {
                throw malformedEnvelope(context, null);
            }
        } else {
            if (acceptsExternalPayload(context)) {
                return new ResolvedPayload(data, true);
            }
            throw malformedEnvelope(context, null);
        }

        var hasData = envelope.has("data") && envelope.get("data").isTextual();
        var hasFile = envelope.has("file") && envelope.get("file").isTextual();
        if (hasData == hasFile) {
            throw malformedEnvelope(context, null);
        }
        if (hasData) {
            return new ResolvedPayload(envelope.get("data").textValue(), false);
        }
        var owner = payloadOwner(envelope, context);
        return new ResolvedPayload(readPayload(envelope.get("file").textValue(), owner, context), false);
    }

    private String readPayload(String fileValue, PayloadOwner owner, SerDesContext context) {
        var file = Path.of(fileValue).toAbsolutePath().normalize();
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
            var serialized = Files.readString(realFile, StandardCharsets.UTF_8);
            var expectedFileName = payloadFileName(serialized, owner.entityId());
            if (!realFile.getFileName().toString().equals(expectedFileName)) {
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
                || !fileName.toString().matches(Pattern.quote(encode(owner.entityId())) + "-[0-9a-f]{64}\\.json")) {
            throw new SerDesException("Filesystem SerDes file is not valid for its declared durable entity");
        }
    }

    private static PayloadOwner payloadOwner(JsonNode envelope, SerDesContext context) {
        var hasOwnerArn = envelope.has("ownerDurableExecutionArn")
                && envelope.get("ownerDurableExecutionArn").isTextual();
        var hasOwnerEntity =
                envelope.has("ownerEntityId") && envelope.get("ownerEntityId").isTextual();
        if (hasOwnerArn != hasOwnerEntity) {
            throw malformedEnvelope(context, null);
        }

        var owner = hasOwnerArn
                ? new PayloadOwner(
                        envelope.get("ownerDurableExecutionArn").textValue(),
                        envelope.get("ownerEntityId").textValue())
                : new PayloadOwner(context.durableExecutionArn(), context.entityId());
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
        return envelope != null
                && envelope.isObject()
                && envelope.has(ENVELOPE_MARKER)
                && envelope.get(ENVELOPE_MARKER).isIntegralNumber()
                && envelope.get(ENVELOPE_MARKER).intValue() == ENVELOPE_VERSION;
    }

    private static boolean acceptsExternalPayload(SerDesContext context) {
        return context.payloadKind() == SerDesPayloadKind.INPUT
                || context.operationType() == OperationType.CALLBACK
                || context.operationType() == OperationType.CHAINED_INVOKE;
    }

    private static SerDesException malformedEnvelope(SerDesContext context, Throwable cause) {
        var message = "Invalid filesystem SerDes envelope for entity '" + context.entityId() + "'";
        return cause == null ? new SerDesException(message) : new SerDesException(message, cause);
    }

    private String encodeEnvelope(String data, Path file, Map<String, Object> preview, SerDesContext context) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put(ENVELOPE_MARKER, ENVELOPE_VERSION);
        if (data != null) {
            envelope.put("data", data);
        } else {
            envelope.put("file", file.toString());
            envelope.put("ownerDurableExecutionArn", context.durableExecutionArn());
            envelope.put("ownerEntityId", context.entityId());
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

    private Map<String, Object> generatePreview(Object value, SerDesContext context) {
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
        return envelope.getBytes(StandardCharsets.UTF_8).length <= CHECKPOINT_ENVELOPE_LIMIT_BYTES;
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

    private Path resolvePayloadPath(String serialized, SerDesContext context) {
        var directory = resolveExecutionDirectory(context.durableExecutionArn());
        var fileName = payloadFileName(serialized, context.entityId());
        var file = directory.resolve(fileName).normalize();
        if (!file.startsWith(directory)) {
            throw new SerDesException("Resolved filesystem payload path is outside the execution directory");
        }
        return file;
    }

    private String payloadFileName(String serialized, String entityId) {
        return encode(entityId) + "-" + sha256(serialized) + ".json";
    }

    private void writePayload(String serialized, Path file) throws IOException {
        var directory = file.getParent();
        var realBasePath = createDirectoriesWithoutSymbolicLinks(directory);
        rejectSymbolicLinks(file);
        var realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(realBasePath)) {
            throw new SerDesException("Filesystem SerDes directory resolves outside the configured base path");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            var existing = Files.readString(file, StandardCharsets.UTF_8);
            if (!existing.equals(serialized)) {
                throw new SerDesException("Filesystem SerDes content-addressed file contains unexpected data");
            }
            return;
        }

        var temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, serialized, StandardCharsets.UTF_8);
            moveWithoutReplacement(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
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

    private static void moveWithoutReplacement(Path temporary, Path file) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(temporary, file);
            } catch (FileAlreadyExistsException ignored) {
                // Another thread or invocation already persisted the same content-addressed payload.
            }
        } catch (FileAlreadyExistsException ignored) {
            // Another thread or invocation already persisted the same content-addressed payload.
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
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
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
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record PayloadOwner(String durableExecutionArn, String entityId) {}

    private record ResolvedPayload(String serialized, boolean external) {}

    /** Builder for {@link FileSystemSerDes}. */
    public static final class Builder {
        private final Path basePath;
        private final boolean stageMode;
        private FileSystemStorageMode storageMode = FileSystemStorageMode.ALWAYS;
        private FileSystemPathEncoding pathEncoding = FileSystemPathEncoding.URI;
        private SerDes delegate;
        private Function<Object, Map<String, Object>> previewGenerator;

        private Builder(Path basePath, boolean stageMode) {
            this.basePath = Objects.requireNonNull(basePath, "basePath cannot be null");
            this.stageMode = stageMode;
            this.delegate = stageMode ? null : new JacksonSerDes();
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
         * Sets the value codec used by standalone mode.
         *
         * @throws IllegalStateException when called on a stage builder
         */
        public Builder delegate(SerDes delegate) {
            if (stageMode) {
                throw new IllegalStateException("FileSystemSerDes stage mode does not use a delegate");
            }
            this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
            return this;
        }

        public Builder previewGenerator(Function<Object, Map<String, Object>> previewGenerator) {
            this.previewGenerator = Objects.requireNonNull(previewGenerator, "previewGenerator cannot be null");
            return this;
        }

        public FileSystemSerDes build() {
            return new FileSystemSerDes(this);
        }
    }
}
