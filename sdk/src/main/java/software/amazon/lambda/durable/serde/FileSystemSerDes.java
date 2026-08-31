// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * A SerDes that stores checkpoint payloads on a durable shared filesystem.
 *
 * <p>Do not use Lambda's ephemeral {@code /tmp} storage. The base path must be available to every execution environment
 * that can serialize or deserialize the payload, such as an EFS mount or an S3 Files mount whose synchronization
 * tradeoffs are acceptable for the workload.
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
            return delegate.deserialize(envelope.get("data").textValue(), typeToken);
        }

        var serialized = readPayload(envelope.get("file").textValue());
        var expected = envelope.get("sha256").textValue();
        if (!expected.equals(sha256(serialized))) {
            throw new SerDesException("Filesystem SerDes payload digest does not match stored content");
        }
        return delegate.deserialize(serialized, typeToken);
    }

    private String inlineEnvelope(String serialized) {
        var envelope = ENVELOPE_MAPPER.createObjectNode();
        envelope.put(ENVELOPE_MARKER, ENVELOPE_VERSION);
        envelope.put("data", serialized);
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
            if (data.contains(ENVELOPE_MARKER)) {
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
        if (hasData) {
            return node.size() == 2;
        }
        if (!node.has("sha256")
                || !node.get("sha256").isTextual()
                || !SHA_256_DIGEST_PATTERN
                        .matcher(node.get("sha256").textValue())
                        .matches()) {
            return false;
        }
        var hasPreview = node.has("preview");
        return (!hasPreview || node.get("preview").isObject()) && node.size() == (hasPreview ? 4 : 3);
    }

    private Path payloadPath(SerDesContext context, String digest) {
        var directory = executionDirectory(context.durableExecutionArn());
        var fileName = encode(context.entityId()) + "-" + digest + ".json";
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
            Files.createDirectories(file.getParent());
            var realBase = basePath.toRealPath();
            var realParent = file.getParent().toRealPath();
            if (!realParent.startsWith(realBase)) {
                throw new SerDesException("Filesystem SerDes path resolves outside the configured base path");
            }
            try {
                Files.writeString(
                        file,
                        serialized,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                var existing = readPayload(file.toString());
                if (!existing.equals(serialized)) {
                    throw new SerDesException("Filesystem SerDes payload file already exists with different content");
                }
            }
        } catch (IOException e) {
            throw new SerDesException("Failed to store filesystem SerDes payload", e);
        }
    }

    private String readPayload(String fileValue) {
        var file = Path.of(fileValue).toAbsolutePath().normalize();
        if (!file.startsWith(basePath)) {
            throw new SerDesException("Filesystem SerDes file is outside the configured base path");
        }
        try {
            var realBase = basePath.toRealPath();
            var realFile = file.toRealPath();
            if (!realFile.startsWith(realBase)) {
                throw new SerDesException("Filesystem SerDes file resolves outside the configured base path");
            }
            return Files.readString(realFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SerDesException("Failed to load filesystem SerDes payload", e);
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
