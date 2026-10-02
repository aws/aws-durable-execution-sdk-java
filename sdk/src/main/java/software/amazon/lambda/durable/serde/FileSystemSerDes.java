// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * Stores operation payloads on a durable shared filesystem, retaining a small JSON envelope in checkpoints.
 *
 * <p>Configure this serializer on individual operations (for example, {@code StepConfig.builder().serDes(...)}). The
 * global serializer also handles ordinary Lambda input/output without operation identity and is not supported. Existing
 * serializers can be composed using {@link Builder#delegate(SerDes)}.
 *
 * <p>Use a durable shared mount such as EFS. Lambda's ephemeral {@code /tmp} cannot survive replay in a different
 * environment. A mount with delayed remote synchronization can lose payloads even after a successful local write. The
 * mount and base directory must be controlled by the application, not writable by untrusted processes.
 *
 * <p>Each write creates an immutable file so retrying or polling never changes an earlier checkpoint's value. Files are
 * not automatically deleted: retain them for the execution's full replay lifetime, then apply external cleanup.
 * Configuration is immutable; custom delegates and preview generators must support concurrent calls.
 */
public final class FileSystemSerDes implements SerDes {
    // Leave 1 KiB below the service's 256 KiB limit for checkpoint metadata.
    private static final int MAX_ENVELOPE_BYTES = 255 * 1024;
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final Path basePath;
    private final FileSystemStorageMode storageMode;
    private final FileSystemPathEncoding pathEncoding;
    private final SerDes delegate;
    private final Function<Object, Map<String, Object>> previewGenerator;

    private FileSystemSerDes(Builder builder) {
        basePath = requireNonNull(builder.basePath, "basePath").toAbsolutePath().normalize();
        storageMode = requireNonNull(builder.storageMode, "storageMode");
        pathEncoding = requireNonNull(builder.pathEncoding, "pathEncoding");
        delegate = requireNonNull(builder.delegate, "delegate");
        previewGenerator = builder.previewGenerator;
    }

    /** Creates a builder; defaults to ALWAYS, URI, and JacksonSerDes. No files are created until serialization. */
    public static Builder builder(Path basePath) {
        return new Builder(basePath);
    }

    @Override
    public String serialize(Object value) {
        throw missingContext();
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        throw missingContext();
    }

    @Override
    public String serialize(Object value, SerDesContext context) {
        validateContext(context);
        var serialized = delegate.serialize(value, context);
        if (serialized == null) return null;
        if (storageMode == FileSystemStorageMode.OVERFLOW) {
            var inline = encodeEnvelope(Map.of("data", serialized));
            if (fitsCheckpoint(inline)) return inline;
        }
        var preview = previewGenerator == null ? null : previewGenerator.apply(value);
        try {
            return writeFile(serialized, preview, context);
        } catch (IOException e) {
            throw new SerDesException("Cannot write filesystem payload under " + basePath, e);
        }
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken, SerDesContext context) {
        validateContext(context);
        if (data == null) return null;
        var envelope = decodeEnvelope(data);
        String serialized;
        try {
            serialized = envelope.has("file")
                    ? readFile(Path.of(envelope.get("file").textValue()))
                    : envelope.get("data").textValue();
        } catch (IOException | IllegalArgumentException e) {
            throw new SerDesException(
                    "Cannot read filesystem payload; its shared mount must remain available for replay", e);
        }
        return delegate.deserialize(serialized, typeToken, context);
    }

    private String writeFile(String serialized, Map<String, Object> preview, SerDesContext context) throws IOException {
        var directory = executionDirectory(context.durableExecutionArn());
        var file = Files.createTempFile(directory, encodeSegment(context.entityId()) + "-", ".json");
        try {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("file", file.toString());
            if (preview != null) fields.put("preview", preview);
            var envelope = encodeEnvelope(fields);
            if (!fitsCheckpoint(envelope)) {
                throw new SerDesException(
                        "Filesystem pointer and preview exceed the 255 KiB checkpoint envelope limit");
            }
            writePayload(file, serialized);
            return envelope;
        } catch (IOException | RuntimeException | Error e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private static void writePayload(Path file, String serialized) throws IOException {
        // The unique file is published only by returning its pointer after the complete write and flush.
        try (var channel = FileChannel.open(file, WRITE, NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(serialized.getBytes(UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private Path executionDirectory(String arn) throws IOException {
        Files.createDirectories(basePath);
        var directory = basePath;
        var segments = pathEncoding == FileSystemPathEncoding.HASH ? new String[] {arn} : arn.split("/", -1);
        for (var segment : segments) {
            directory = directory.resolve(encodeSegment(segment));
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw e;
            }
        }
        return directory;
    }

    private String readFile(Path file) throws IOException {
        var normalized = file.normalize();
        if (!file.isAbsolute() || !normalized.startsWith(basePath) || normalized.equals(basePath)) {
            throw new SerDesException("Filesystem payload must be within the configured base path");
        }
        var current = basePath;
        for (var segment : basePath.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current))
                throw new SerDesException("Filesystem payload paths cannot contain symlinks");
        }
        try (var channel = Files.newByteChannel(normalized, READ, NOFOLLOW_LINKS);
                var stream = Channels.newInputStream(channel)) {
            return new String(stream.readAllBytes(), UTF_8);
        }
    }

    private String encodeSegment(String value) {
        if (pathEncoding == FileSystemPathEncoding.HASH) {
            try {
                return HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required by Java", e);
            }
        }
        var encoded = URLEncoder.encode(value, UTF_8).replace("+", "%20");
        return encoded.isEmpty()
                ? "%EMPTY"
                : encoded.equals(".") || encoded.equals("..") ? encoded.replace(".", "%2E") : encoded;
    }

    private static JsonNode decodeEnvelope(String data) {
        try {
            var envelope = ENVELOPE_MAPPER.readTree(data);
            if (envelope == null || !envelope.isObject() || envelope.has("file") == envelope.has("data")) {
                throw new SerDesException("Filesystem envelope must contain exactly one of 'file' or 'data'");
            }
            var payload = envelope.get(envelope.has("file") ? "file" : "data");
            if (!payload.isTextual()
                    || (envelope.has("file") && payload.textValue().isBlank())) {
                throw new SerDesException(
                        "Filesystem envelope payload must be a string and file paths cannot be blank");
            }
            return envelope;
        } catch (JsonProcessingException e) {
            throw new SerDesException("Invalid filesystem envelope JSON", e);
        }
    }

    private static String encodeEnvelope(Map<String, ?> fields) {
        try {
            return ENVELOPE_MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new SerDesException("Cannot serialize filesystem envelope", e);
        }
    }

    private static boolean fitsCheckpoint(String envelope) {
        return envelope.getBytes(UTF_8).length <= MAX_ENVELOPE_BYTES;
    }

    private static void validateContext(SerDesContext context) {
        if (context == null
                || context.durableExecutionArn() == null
                || context.durableExecutionArn().isBlank()
                || context.entityId() == null
                || context.entityId().isBlank()) throw missingContext();
    }

    private static SerDesException missingContext() {
        return new SerDesException(
                "FileSystemSerDes requires an execution ARN and entity ID. Configure it on an operation, "
                        + "such as StepConfig.serDes, rather than as the global DurableConfig serializer.");
    }

    /** Builder for filesystem storage and delegate serialization. */
    public static final class Builder {
        private final Path basePath;
        private FileSystemStorageMode storageMode = FileSystemStorageMode.ALWAYS;
        private FileSystemPathEncoding pathEncoding = FileSystemPathEncoding.URI;
        private SerDes delegate = new JacksonSerDes();
        private Function<Object, Map<String, Object>> previewGenerator;

        private Builder(Path basePath) {
            this.basePath = basePath;
        }

        /** Sets when payloads are stored in files. */
        public Builder storageMode(FileSystemStorageMode storageMode) {
            this.storageMode = storageMode;
            return this;
        }

        /** Sets how execution and entity identities are encoded in paths. */
        public Builder pathEncoding(FileSystemPathEncoding pathEncoding) {
            this.pathEncoding = pathEncoding;
            return this;
        }

        /** Sets the serializer that encodes payloads; its serialized strings are stored unchanged. */
        public Builder delegate(SerDes delegate) {
            this.delegate = delegate;
            return this;
        }

        /** Sets an optional preview stored alongside file pointers. Return null to omit a preview. */
        public Builder previewGenerator(Function<Object, Map<String, Object>> previewGenerator) {
            this.previewGenerator = previewGenerator;
            return this;
        }

        /** Builds an immutable serializer. */
        public FileSystemSerDes build() {
            return new FileSystemSerDes(this);
        }
    }
}
