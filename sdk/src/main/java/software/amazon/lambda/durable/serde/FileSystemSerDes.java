// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * A SerDes that stores checkpoint payloads on a durable shared filesystem.
 *
 * <p>Do not use Lambda's ephemeral {@code /tmp} storage. The base path must be available to every execution environment
 * that can serialize or deserialize the payload, such as an EFS mount or an S3 Files mount whose synchronization
 * tradeoffs are acceptable for the workload.
 *
 * <p>The filesystem provider must support {@link SecureDirectoryStream}. Directory components are traversed relative to
 * held parent handles with symbolic-link following disabled, and file I/O uses {@link LinkOption#NOFOLLOW_LINKS}.
 *
 * <p>Initial invocation input is serialized normally when no {@link SerDesContext} is available, because the durable
 * execution ARN does not exist until after invocation starts. SDK-managed operation and output payloads are processed
 * using the configured storage mode.
 */
public final class FileSystemSerDes implements SerDes {
    private static final String ENVELOPE_MARKER = "__durable_execution_filesystem_serdes";
    private static final int ENVELOPE_VERSION = 1;
    private static final int DEFAULT_CHECKPOINT_ENVELOPE_LIMIT_BYTES = 256 * 1024 - 1024;
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();
    private static final ObjectReader ENVELOPE_READER = ENVELOPE_MAPPER
            .reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Pattern DURABLE_EXECUTION_ARN_PATTERN = Pattern.compile(
            "^arn:[^:]*:lambda:[^:]*:[^:]*:function:([^:/]+):[^:/]+/durable-execution/([^/]+)/([^/]+)$");
    private static final Pattern SHA_256_DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Path basePath;
    private final FileSystemSerDesMode storageMode;
    private final FileSystemPathEncoding pathEncoding;
    private final SerDes delegate;
    private final int checkpointEnvelopeLimitBytes;
    private final Function<Object, Map<String, Object>> previewGenerator;

    private FileSystemSerDes(Builder builder) {
        basePath = builder.basePath.toAbsolutePath().normalize();
        storageMode = builder.storageMode;
        pathEncoding = builder.pathEncoding;
        delegate = builder.delegate;
        checkpointEnvelopeLimitBytes = builder.checkpointEnvelopeLimitBytes;
        previewGenerator = builder.previewGenerator;
    }

    /** Creates a builder rooted at the given durable shared filesystem path. */
    public static Builder builder(Path basePath) {
        return new Builder(basePath);
    }

    @Override
    public String serialize(Object value) {
        var serialized = delegate.serialize(value);
        if (serialized == null) {
            return null;
        }

        var context = SerDesContext.getCurrentContext();
        if (context == null) {
            return serialized;
        }

        if (storageMode == FileSystemSerDesMode.OVERFLOW) {
            var inlineEnvelope = inlineEnvelope(serialized);
            if (fitsCheckpoint(inlineEnvelope)) {
                return inlineEnvelope;
            }
        }

        return fileEnvelope(value, serialized, context);
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }

        var envelope = parseEnvelope(data);
        if (envelope == null) {
            return delegate.deserialize(data, typeToken);
        }
        if (envelope.hasNonNull("data")) {
            var serialized = envelope.get("data").textValue();
            verifyDigest(serialized, envelope.get("sha256").textValue());
            return delegate.deserialize(serialized, typeToken);
        }

        var serialized = readPayload(envelope.get("file").textValue());
        verifyDigest(serialized, envelope.get("sha256").textValue());
        return delegate.deserialize(serialized, typeToken);
    }

    private String inlineEnvelope(String serialized) {
        var envelope = ENVELOPE_MAPPER.createObjectNode();
        envelope.put(ENVELOPE_MARKER, ENVELOPE_VERSION);
        envelope.put("data", serialized);
        envelope.put("sha256", sha256(serialized));
        return writeEnvelope(envelope);
    }

    private String fileEnvelope(Object value, String serialized, SerDesContext context) {
        var digest = sha256(serialized);
        var file = payloadPath(context, digest);

        var envelope = ENVELOPE_MAPPER.createObjectNode();
        envelope.put(ENVELOPE_MARKER, ENVELOPE_VERSION);
        envelope.put("file", file.toString());
        envelope.put("sha256", digest);
        if (previewGenerator != null) {
            var preview = previewGenerator.apply(value);
            if (preview != null) {
                envelope.set("preview", ENVELOPE_MAPPER.valueToTree(preview));
            }
        }
        var encoded = writeEnvelope(envelope);
        if (!fitsCheckpoint(encoded)) {
            throw new SerDesException("Filesystem SerDes file envelope exceeds the checkpoint payload limit");
        }

        writePayload(file, serialized);
        return encoded;
    }

    private JsonNode parseEnvelope(String data) {
        final JsonNode node;
        try {
            node = ENVELOPE_READER.readTree(data);
        } catch (JsonProcessingException e) {
            if (containsFilesystemMarkerField(data)) {
                throw new SerDesException("Malformed filesystem SerDes envelope", e);
            }
            return null;
        }
        if (node == null || !node.isObject() || !node.has(ENVELOPE_MARKER)) {
            return null;
        }

        if (!node.get(ENVELOPE_MARKER).isIntegralNumber()
                || !node.get(ENVELOPE_MARKER).canConvertToInt()
                || node.get(ENVELOPE_MARKER).intValue() != ENVELOPE_VERSION
                || !isValidEnvelope(node)) {
            throw new SerDesException("Malformed filesystem SerDes envelope");
        }
        return node;
    }

    private static boolean isValidEnvelope(JsonNode node) {
        var hasData = node.has("data") && node.get("data").isTextual();
        var hasFile = node.has("file") && node.get("file").isTextual();
        if (hasData == hasFile) {
            return false;
        }
        if (!node.has("sha256")
                || !node.get("sha256").isTextual()
                || !SHA_256_DIGEST_PATTERN
                        .matcher(node.get("sha256").textValue())
                        .matches()) {
            return false;
        }
        if (hasData) {
            return node.size() == 3;
        }
        var hasPreview = node.has("preview");
        return (!hasPreview || node.get("preview").isObject()) && node.size() == (hasPreview ? 4 : 3);
    }

    private static boolean containsFilesystemMarkerField(String data) {
        try (var parser = ENVELOPE_MAPPER.createParser(data)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            var depth = 1;
            while (parser.nextToken() != null) {
                var token = parser.currentToken();
                if (token == JsonToken.FIELD_NAME && depth == 1 && ENVELOPE_MARKER.equals(parser.currentName())) {
                    return true;
                }
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                    if (depth == 0) {
                        return false;
                    }
                }
            }
        } catch (IOException ignored) {
            // The caller delegates malformed input unless a top-level marker field was observed before the failure.
        }
        return false;
    }

    private static void verifyDigest(String serialized, String expected) {
        if (!expected.equals(sha256(serialized))) {
            throw new SerDesException("Filesystem SerDes payload digest does not match stored content");
        }
    }

    private Path payloadPath(SerDesContext context, String digest) {
        var directory = executionDirectory(context.durableExecutionArn());
        var fileName = encode(context.entityId()) + "-" + digest + "-" + UUID.randomUUID() + ".json";
        var file = directory.resolve(fileName).toAbsolutePath().normalize();
        if (!file.startsWith(basePath)) {
            throw new SerDesException("Filesystem SerDes path escapes the configured base path");
        }
        return file;
    }

    private Path executionDirectory(String durableExecutionArn) {
        if (pathEncoding == FileSystemPathEncoding.URI) {
            var match = DURABLE_EXECUTION_ARN_PATTERN.matcher(durableExecutionArn);
            if (match.matches()) {
                return basePath.resolve(encode(match.group(1)))
                        .resolve(encode(match.group(2)))
                        .resolve(encode(match.group(3)));
            }
        }
        return basePath.resolve(encode(durableExecutionArn));
    }

    private void writePayload(Path file, String serialized) {
        try {
            try (var secureDirectory = openSecureDirectory(file.getParent(), true)) {
                var created = false;
                rejectSymbolicLinkIfPresent(secureDirectory.directory(), file.getFileName(), "payload file");
                try (var channel = secureDirectory
                        .directory()
                        .newByteChannel(
                                file.getFileName(),
                                Set.of(
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS))) {
                    created = true;
                    var buffer = ByteBuffer.wrap(serialized.getBytes(StandardCharsets.UTF_8));
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
        } catch (IOException e) {
            throw classifyFileSystemFailure("store", e);
        }
    }

    private String readPayload(String fileValue) {
        var file = basePath.getFileSystem().getPath(fileValue).toAbsolutePath().normalize();
        if (!file.startsWith(basePath)) {
            throw new SerDesException("Filesystem SerDes file is outside the configured base path");
        }
        try {
            byte[] storedData;
            try (var secureDirectory = openSecureDirectory(file.getParent(), false)) {
                rejectSymbolicLinkIfPresent(secureDirectory.directory(), file.getFileName(), "payload file");
                try (var channel = secureDirectory
                                .directory()
                                .newByteChannel(
                                        file.getFileName(),
                                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                        var input = Channels.newInputStream(channel)) {
                    storedData = input.readAllBytes();
                }
            }
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(storedData))
                    .toString();
        } catch (IOException e) {
            throw classifyFileSystemFailure("load", e);
        }
    }

    private static SerDesException classifyFileSystemFailure(String action, IOException failure) {
        var message = "Failed to " + action + " filesystem SerDes payload";
        if (failure instanceof AccessDeniedException
                || failure instanceof NotDirectoryException
                || failure instanceof FileSystemLoopException) {
            return new SerDesException(message, failure);
        }
        return new RetryableSerDesException(message, failure);
    }

    private SecureDirectoryHandle openSecureDirectory(Path directory, boolean createMissing) throws IOException {
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
            var currentPath = root;
            for (var component : root.relativize(directory)) {
                var nextPath = currentPath.resolve(component);
                DirectoryStream<Path> next;
                rejectSymbolicLinkIfPresent(current, component, "directory");
                try {
                    next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException missing) {
                    if (!createMissing) {
                        throw missing;
                    }
                    try {
                        Files.createDirectory(nextPath);
                    } catch (FileAlreadyExistsException ignored) {
                        // Validate and open the entry relative to the held parent directory below.
                    }
                    next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                }
                current = requireSecureDirectoryStream(next, openedStreams);
                currentPath = nextPath;
            }
            return new SecureDirectoryHandle(current, openedStreams);
        } catch (IOException | RuntimeException failure) {
            closeDirectoryStreams(openedStreams, failure);
            throw failure;
        }
    }

    private static void rejectSymbolicLinkIfPresent(
            SecureDirectoryStream<Path> directory, Path entry, String description) throws IOException {
        var attributes = directory.getFileAttributeView(entry, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes == null) {
            throw new SerDesException("Filesystem provider cannot inspect " + description + " without following links");
        }
        try {
            if (attributes.readAttributes().isSymbolicLink()) {
                throw new SerDesException("Filesystem SerDes " + description + " cannot be a symbolic link");
            }
        } catch (NoSuchFileException ignored) {
            // Missing entries are handled by the caller as either creatable directories or retryable read failures.
        }
    }

    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> requireSecureDirectoryStream(
            DirectoryStream<Path> stream, List<DirectoryStream<Path>> openedStreams) {
        openedStreams.add(stream);
        if (stream instanceof SecureDirectoryStream<?> secureStream) {
            return (SecureDirectoryStream<Path>) secureStream;
        }
        throw new SerDesException("FileSystemSerDes requires a filesystem provider with SecureDirectoryStream support");
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

    private String encode(String value) {
        return pathEncoding == FileSystemPathEncoding.HASH ? sha256(value) : percentEncode(value);
    }

    private boolean fitsCheckpoint(String envelope) {
        return envelope.getBytes(StandardCharsets.UTF_8).length <= checkpointEnvelopeLimitBytes;
    }

    private static String percentEncode(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var encoded = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int valueByte = raw & 0xff;
            if ((valueByte >= 'a' && valueByte <= 'z')
                    || (valueByte >= 'A' && valueByte <= 'Z')
                    || (valueByte >= '0' && valueByte <= '9')
                    || valueByte == '-'
                    || valueByte == '_'
                    || valueByte == '.'
                    || valueByte == '~') {
                encoded.append((char) valueByte);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((valueByte >>> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(valueByte & 0xf, 16)));
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

    private static String writeEnvelope(JsonNode envelope) {
        try {
            return ENVELOPE_MAPPER.writeValueAsString(envelope);
        } catch (IOException e) {
            throw new SerDesException("Failed to create filesystem SerDes envelope", e);
        }
    }

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

    /** Builder for {@link FileSystemSerDes}. */
    public static final class Builder {
        private final Path basePath;
        private FileSystemSerDesMode storageMode = FileSystemSerDesMode.ALWAYS;
        private FileSystemPathEncoding pathEncoding = FileSystemPathEncoding.URI;
        private SerDes delegate = new JacksonSerDes();
        private int checkpointEnvelopeLimitBytes = DEFAULT_CHECKPOINT_ENVELOPE_LIMIT_BYTES;
        private Function<Object, Map<String, Object>> previewGenerator;

        private Builder(Path basePath) {
            this.basePath = Objects.requireNonNull(basePath, "basePath cannot be null");
        }

        public Builder storageMode(FileSystemSerDesMode storageMode) {
            this.storageMode = Objects.requireNonNull(storageMode, "storageMode cannot be null");
            return this;
        }

        public Builder pathEncoding(FileSystemPathEncoding pathEncoding) {
            this.pathEncoding = Objects.requireNonNull(pathEncoding, "pathEncoding cannot be null");
            return this;
        }

        public Builder delegate(SerDes delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
            return this;
        }

        /** Sets the maximum UTF-8 size of an inline or file checkpoint envelope. */
        public Builder checkpointEnvelopeLimitBytes(int checkpointEnvelopeLimitBytes) {
            if (checkpointEnvelopeLimitBytes <= 0) {
                throw new IllegalArgumentException("checkpointEnvelopeLimitBytes must be positive");
            }
            this.checkpointEnvelopeLimitBytes = checkpointEnvelopeLimitBytes;
            return this;
        }

        public Builder previewGenerator(Function<Object, Map<String, Object>> previewGenerator) {
            this.previewGenerator = Objects.requireNonNull(previewGenerator, "previewGenerator cannot be null");
            return this;
        }

        /** Configures structured preview generation from the original value. */
        public Builder previewConfig(PreviewConfig previewConfig) {
            Objects.requireNonNull(previewConfig, "previewConfig cannot be null");
            this.previewGenerator = value -> SerDesPreview.buildPreview(value, previewConfig);
            return this;
        }

        public FileSystemSerDes build() {
            return new FileSystemSerDes(this);
        }
    }
}
