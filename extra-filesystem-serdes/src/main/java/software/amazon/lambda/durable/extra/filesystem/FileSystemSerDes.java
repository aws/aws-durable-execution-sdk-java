// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extra.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;

/**
 * A SerDes that stores delegate-serialized payloads on a durable shared filesystem.
 *
 * <p>Do not use Lambda's ephemeral {@code /tmp} storage. Use a durable shared mount such as EFS, or S3 Files only when
 * its synchronization and crash-durability tradeoffs are acceptable for the workload.
 */
public final class FileSystemSerDes implements SerDes {
    private static final int OVERFLOW_THRESHOLD_BYTES = 256 * 1024 - 1024;
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();
    private static final Pattern DURABLE_EXECUTION_ARN_PATTERN = Pattern.compile(
            "^arn:[^:]*:lambda:[^:]*:[^:]*:function:([^:/]+):[^:/]+/durable-execution/([^/]+)/([^/]+)$");

    private final Path basePath;
    private final FileSystemStorageMode storageMode;
    private final FileSystemPathEncoding pathEncoding;
    private final SerDes delegate;
    private final Function<Object, Map<String, Object>> previewGenerator;

    private FileSystemSerDes(Builder builder) {
        basePath = builder.basePath.toAbsolutePath().normalize();
        storageMode = builder.storageMode;
        pathEncoding = builder.pathEncoding;
        delegate = builder.delegate;
        previewGenerator = builder.previewGenerator;
    }

    public static Builder builder(Path basePath) {
        return new Builder(basePath);
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        var context = requireContext();
        var serialized = delegate.serialize(value);
        if (serialized == null) {
            throw new SerDesException("Delegate SerDes returned null for a non-null value");
        }
        try {
            var inlineEnvelope = ENVELOPE_MAPPER.writeValueAsString(Map.of("data", serialized));
            if (storageMode == FileSystemStorageMode.OVERFLOW
                    && inlineEnvelope.getBytes(StandardCharsets.UTF_8).length <= OVERFLOW_THRESHOLD_BYTES) {
                return inlineEnvelope;
            }
            var file = writePayload(serialized, context);
            var preview = previewGenerator != null ? previewGenerator.apply(value) : null;
            return preview == null
                    ? ENVELOPE_MAPPER.writeValueAsString(Map.of("file", file.toString()))
                    : ENVELOPE_MAPPER.writeValueAsString(Map.of("file", file.toString(), "preview", preview));
        } catch (IOException e) {
            throw new SerDesException("Failed to store filesystem payload for entity '" + context.entityId() + "'", e);
        }
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        if (data == null) {
            return null;
        }
        var context = requireContext();
        try {
            JsonNode envelope = ENVELOPE_MAPPER.readTree(data);
            var hasData = envelope.has("data") && envelope.get("data").isTextual();
            var hasFile = envelope.has("file") && envelope.get("file").isTextual();
            if (hasData == hasFile) {
                throw new SerDesException("Filesystem SerDes envelope must contain exactly one of 'data' or 'file'");
            }
            String serialized;
            if (hasData) {
                serialized = envelope.get("data").textValue();
            } else {
                var file = Path.of(envelope.get("file").textValue())
                        .toAbsolutePath()
                        .normalize();
                if (!file.startsWith(basePath)) {
                    throw new SerDesException("Filesystem SerDes file is outside the configured base path");
                }
                serialized = Files.readString(file, StandardCharsets.UTF_8);
            }
            return delegate.deserialize(serialized, typeToken);
        } catch (SerDesException e) {
            throw e;
        } catch (Exception e) {
            throw new SerDesException("Failed to load filesystem payload for entity '" + context.entityId() + "'", e);
        }
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

    private Path writePayload(String serialized, SerDesContext context) throws IOException {
        var directory = resolveExecutionDirectory(context.durableExecutionArn());
        Files.createDirectories(directory);
        var file = directory.resolve(encode(context.entityId()) + ".json").normalize();
        if (!file.startsWith(directory)) {
            throw new SerDesException("Resolved filesystem payload path is outside the execution directory");
        }
        var temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, serialized, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return file;
    }

    private Path resolveExecutionDirectory(String durableExecutionArn) {
        Path directory;
        if (pathEncoding == FileSystemPathEncoding.URI) {
            var matcher = DURABLE_EXECUTION_ARN_PATTERN.matcher(durableExecutionArn);
            if (matcher.matches()) {
                directory = basePath.resolve(matcher.group(1))
                        .resolve(matcher.group(2))
                        .resolve(matcher.group(3))
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

    /** Builder for {@link FileSystemSerDes}. */
    public static final class Builder {
        private final Path basePath;
        private FileSystemStorageMode storageMode = FileSystemStorageMode.ALWAYS;
        private FileSystemPathEncoding pathEncoding = FileSystemPathEncoding.URI;
        private SerDes delegate = new JacksonSerDes();
        private Function<Object, Map<String, Object>> previewGenerator;

        private Builder(Path basePath) {
            this.basePath = Objects.requireNonNull(basePath, "basePath cannot be null");
        }

        public Builder storageMode(FileSystemStorageMode storageMode) {
            this.storageMode = Objects.requireNonNull(storageMode);
            return this;
        }

        public Builder pathEncoding(FileSystemPathEncoding pathEncoding) {
            this.pathEncoding = Objects.requireNonNull(pathEncoding);
            return this;
        }

        public Builder delegate(SerDes delegate) {
            this.delegate = Objects.requireNonNull(delegate);
            return this;
        }

        public Builder previewGenerator(Function<Object, Map<String, Object>> previewGenerator) {
            this.previewGenerator = Objects.requireNonNull(previewGenerator);
            return this;
        }

        public FileSystemSerDes build() {
            return new FileSystemSerDes(this);
        }
    }
}
