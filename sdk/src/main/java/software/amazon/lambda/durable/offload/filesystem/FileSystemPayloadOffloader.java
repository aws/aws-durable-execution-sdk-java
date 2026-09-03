// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.filesystem;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.RetryablePayloadOffloadException;
import software.amazon.lambda.durable.execution.PayloadCodec;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.PayloadStorageMode;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;

/**
 * Stores serialized payloads on a durable, shared filesystem.
 *
 * <p>Do not use Lambda's ephemeral {@code /tmp} storage. Use a durable shared mount such as EFS, or S3 Files only when
 * its synchronization and crash-durability tradeoffs are acceptable for the workload.
 *
 * <p>Payload files are immutable and published with a single {@code CREATE_NEW} write. Each SDK envelope records the
 * producing execution and entity plus a SHA-256 content digest. Loads validate ownership, path, file name, and content
 * before returning data.
 *
 * <p>The configured base path and all ancestors must already exist. Payload files are direct children of that path. The
 * filesystem provider must support {@link SecureDirectoryStream}; directory handles remain open through file I/O and
 * symbolic-link following is disabled.
 */
public final class FileSystemPayloadOffloader implements PayloadOffloader {
    private static final int DEFAULT_CHECKPOINT_ENVELOPE_LIMIT_BYTES = 256 * 1024 - 1024;
    private static final int MAX_URI_OWNER_PREFIX_LENGTH = 32;
    private static final Pattern SHA_256_DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Path basePath;
    private final PayloadOffloadMode storageMode;
    private final FileSystemPathEncoding pathEncoding;
    private final int checkpointEnvelopeLimitBytes;
    private final PayloadPreviewGenerator previewGenerator;

    private FileSystemPayloadOffloader(Builder builder) {
        basePath = builder.basePath.toAbsolutePath().normalize();
        storageMode = builder.storageMode;
        pathEncoding = builder.pathEncoding;
        checkpointEnvelopeLimitBytes = builder.checkpointEnvelopeLimitBytes;
        previewGenerator = builder.previewGenerator;
    }

    /** Creates a filesystem offloader builder. */
    public static Builder builder(Path basePath) {
        return new Builder(basePath);
    }

    @Override
    public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
        Objects.requireNonNull(serializedPayload, "serializedPayload cannot be null");
        context = requireContext(context);
        var payloadDigest = sha256(serializedPayload);
        if (storageMode == PayloadOffloadMode.OVERFLOW) {
            var inlinePayload = OffloadedPayload.inline(
                            serializedPayload, context.durableExecutionArn(), context.entityId(), payloadDigest)
                    .bindProducer(context, payloadDigest);
            if (fitsCheckpoint(inlinePayload)) {
                return inlinePayload;
            }
        }

        var path = resolvePayloadPath(payloadDigest, context);
        var preview = generatePreview(serializedPayload, context);
        var referencePayload = OffloadedPayload.reference(
                        path.toString(), preview, context.durableExecutionArn(), context.entityId(), payloadDigest)
                .bindProducer(context, payloadDigest);
        if (!fitsCheckpoint(referencePayload)) {
            throw new PayloadOffloadException(
                    "Filesystem payload envelope exceeds the checkpoint limit for entity '" + context.entityId() + "'");
        }
        try {
            writePayload(serializedPayload, path);
            return referencePayload;
        } catch (IOException e) {
            throw classifyIoFailure("store", context, e);
        }
    }

    @Override
    public String load(OffloadedPayload payload, PayloadOffloadContext context) {
        Objects.requireNonNull(payload, "payload cannot be null");
        context = requireContext(context);
        requireIntegrityMetadata(payload, context);
        validateOwner(payload, context);
        if (payload.mode() == PayloadStorageMode.INLINE) {
            verifyDigest(payload.data(), payload.payloadDigest(), context);
            return payload.data();
        }

        var path = basePath.getFileSystem()
                .getPath(payload.reference())
                .toAbsolutePath()
                .normalize();
        validatePayloadPath(path, payload);
        try {
            byte[] storedData;
            try (var directory = openSecureDirectory(path.getParent());
                    var channel = openPayloadForRead(directory.directory(), path.getFileName(), context);
                    var input = Channels.newInputStream(channel)) {
                storedData = input.readAllBytes();
            }
            var serialized = new String(storedData, StandardCharsets.UTF_8);
            verifyDigest(serialized, payload.payloadDigest(), context);
            return serialized;
        } catch (IOException e) {
            throw classifyIoFailure("load", context, e);
        }
    }

    private boolean fitsCheckpoint(OffloadedPayload payload) {
        return PayloadCodec.envelopeSizeBytes(payload) <= checkpointEnvelopeLimitBytes;
    }

    private Map<String, Object> generatePreview(String serializedPayload, PayloadOffloadContext context) {
        if (previewGenerator == null) {
            return null;
        }
        try {
            return previewGenerator.generate(serializedPayload, context);
        } catch (RuntimeException e) {
            if (e instanceof RetryablePayloadOffloadException retryablePayloadOffloadException) {
                throw retryablePayloadOffloadException;
            }
            if (e instanceof PayloadOffloadException payloadOffloadException) {
                throw payloadOffloadException;
            }
            throw new PayloadOffloadException(
                    "Failed to generate filesystem payload preview for entity '" + context.entityId() + "'", e);
        }
    }

    private static PayloadOffloadContext requireContext(PayloadOffloadContext context) {
        if (context == null
                || context.durableExecutionArn() == null
                || context.durableExecutionArn().isBlank()
                || context.entityId() == null
                || context.entityId().isBlank()) {
            throw new PayloadOffloadException("FileSystemPayloadOffloader requires durableExecutionArn and entityId");
        }
        return context;
    }

    private static void requireIntegrityMetadata(OffloadedPayload payload, PayloadOffloadContext context) {
        if (!payload.hasIntegrityMetadata()) {
            throw new PayloadOffloadException("Filesystem payload is missing ownership or digest metadata for entity '"
                    + context.entityId() + "'");
        }
    }

    private static void validateOwner(OffloadedPayload payload, PayloadOffloadContext context) {
        var sameOwner = payload.ownerDurableExecutionArn().equals(context.durableExecutionArn())
                && payload.ownerEntityId().equals(context.entityId());
        if (!sameOwner && !acceptsCrossExecutionReference(context)) {
            throw new PayloadOffloadException("Filesystem payload belongs to a different durable entity");
        }
    }

    private static boolean acceptsCrossExecutionReference(PayloadOffloadContext context) {
        return context.payloadKind() == SerDesPayloadKind.INPUT
                || context.operationType() == OperationType.CHAINED_INVOKE;
    }

    private void validatePayloadPath(Path path, OffloadedPayload payload) {
        var expectedPrefix = payloadOwnerPrefix(payload.ownerDurableExecutionArn(), payload.ownerEntityId())
                + "-"
                + payload.payloadDigest()
                + "-";
        var fileName = path.getFileName();
        if (fileName == null
                || path.getParent() == null
                || !path.getParent().equals(basePath)
                || !fileName.toString().startsWith(expectedPrefix)
                || !fileName.toString().endsWith(".payload")) {
            throw new PayloadOffloadException("Filesystem payload path is not valid for its declared durable entity");
        }
    }

    private Path resolvePayloadPath(String payloadDigest, PayloadOffloadContext context) {
        var fileName = payloadOwnerPrefix(context.durableExecutionArn(), context.entityId())
                + "-"
                + payloadDigest
                + "-"
                + UUID.randomUUID()
                + ".payload";
        var file = basePath.resolve(fileName).normalize();
        if (!basePath.equals(file.getParent())) {
            throw new PayloadOffloadException("Resolved filesystem payload path is outside the configured base path");
        }
        return file;
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
        if (".".contentEquals(encoded) || "..".contentEquals(encoded)) {
            return encoded.toString().replace(".", "%2E");
        }
        return encoded.toString();
    }

    private void writePayload(String serializedPayload, Path file) throws IOException {
        try (var directory = openSecureDirectory(file.getParent())) {
            if (Files.getFileStore(basePath).isReadOnly()) {
                throw new PayloadOffloadException("Filesystem payload base path is read-only");
            }
            var created = false;
            try (var channel = directory
                    .directory()
                    .newByteChannel(
                            file.getFileName(),
                            Set.of(
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS))) {
                created = true;
                var buffer = ByteBuffer.wrap(serializedPayload.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            } catch (FileAlreadyExistsException failure) {
                throw failure;
            } catch (IOException failure) {
                if (created) {
                    try {
                        directory.directory().deleteFile(file.getFileName());
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        } catch (ReadOnlyFileSystemException failure) {
            throw new PayloadOffloadException("Filesystem payload base path is read-only", failure);
        }
    }

    private SecureDirectoryHandle openSecureDirectory(Path directory) throws IOException {
        if (directory == null || !directory.startsWith(basePath)) {
            throw new PayloadOffloadException("Filesystem payload directory is outside the configured base path");
        }
        var root = basePath.getRoot();
        if (root == null) {
            throw new PayloadOffloadException("Filesystem payload base path must be absolute");
        }

        var openedStreams = new ArrayList<DirectoryStream<Path>>();
        try {
            var current = requireSecureDirectoryStream(Files.newDirectoryStream(root), openedStreams);
            for (var component : root.relativize(directory)) {
                try {
                    var attributes = readAttributes(current, component);
                    if (attributes.isSymbolicLink()) {
                        throw new PayloadOffloadException("Filesystem payload base path cannot contain symbolic links");
                    }
                    if (!attributes.isDirectory()) {
                        throw new PayloadOffloadException(
                                "Filesystem payload base path and all ancestors must be directories");
                    }
                    current = requireSecureDirectoryStream(
                            current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS), openedStreams);
                } catch (NoSuchFileException missing) {
                    throw new PayloadOffloadException(
                            "Filesystem payload base path and all ancestors must already exist", missing);
                }
            }
            return new SecureDirectoryHandle(current, openedStreams);
        } catch (IOException | RuntimeException failure) {
            closeDirectoryStreams(openedStreams, failure);
            throw failure;
        }
    }

    private java.nio.channels.SeekableByteChannel openPayloadForRead(
            SecureDirectoryStream<Path> directory, Path fileName, PayloadOffloadContext context) throws IOException {
        var attributes = readAttributes(directory, fileName);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new PayloadOffloadException(
                    "Filesystem payload must be a regular file for entity '" + context.entityId() + "'");
        }
        return directory.newByteChannel(fileName, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    private static java.nio.file.attribute.BasicFileAttributes readAttributes(
            SecureDirectoryStream<Path> directory, Path path) throws IOException {
        var view = directory.getFileAttributeView(path, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new PayloadOffloadException("FileSystemPayloadOffloader requires basic file attribute support");
        }
        return view.readAttributes();
    }

    static PayloadOffloadException classifyIoFailure(
            String action, PayloadOffloadContext context, IOException failure) {
        var message = "Failed to " + action + " filesystem payload for entity '" + context.entityId() + "'";
        if (isKnownTransientFailure(failure)) {
            return new RetryablePayloadOffloadException(message, failure);
        }
        return new PayloadOffloadException(message, failure);
    }

    private static boolean isKnownTransientFailure(IOException failure) {
        if (failure instanceof FileAlreadyExistsException
                || failure instanceof InterruptedIOException
                || failure instanceof ClosedByInterruptException) {
            return true;
        }
        if (!(failure instanceof FileSystemException fileSystemFailure) || fileSystemFailure.getReason() == null) {
            return false;
        }
        var reason = fileSystemFailure.getReason().toLowerCase(Locale.ROOT);
        return reason.contains("stale file handle")
                || reason.contains("resource temporarily unavailable")
                || reason.contains("connection reset")
                || reason.contains("connection timed out")
                || reason.contains("network is unreachable")
                || reason.contains("no route to host")
                || reason.contains("transport endpoint is not connected");
    }

    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> requireSecureDirectoryStream(
            DirectoryStream<Path> stream, List<DirectoryStream<Path>> openedStreams) {
        openedStreams.add(stream);
        if (stream instanceof SecureDirectoryStream<?> secureStream) {
            return (SecureDirectoryStream<Path>) secureStream;
        }
        throw new PayloadOffloadException(
                "FileSystemPayloadOffloader requires a filesystem provider with SecureDirectoryStream support");
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

    private static void verifyDigest(String serializedPayload, String expectedDigest, PayloadOffloadContext context) {
        if (!SHA_256_DIGEST_PATTERN.matcher(expectedDigest).matches()
                || !sha256(serializedPayload).equals(expectedDigest)) {
            throw new PayloadOffloadException(
                    "Filesystem payload digest does not match stored content for entity '" + context.entityId() + "'");
        }
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
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

    /** Builder for {@link FileSystemPayloadOffloader}. */
    public static final class Builder {
        private final Path basePath;
        private PayloadOffloadMode storageMode = PayloadOffloadMode.ALWAYS;
        private FileSystemPathEncoding pathEncoding = FileSystemPathEncoding.URI;
        private int checkpointEnvelopeLimitBytes = DEFAULT_CHECKPOINT_ENVELOPE_LIMIT_BYTES;
        private PayloadPreviewGenerator previewGenerator;

        private Builder(Path basePath) {
            this.basePath = Objects.requireNonNull(basePath, "basePath cannot be null");
        }

        public Builder storageMode(PayloadOffloadMode storageMode) {
            this.storageMode = Objects.requireNonNull(storageMode, "storageMode cannot be null");
            return this;
        }

        public Builder pathEncoding(FileSystemPathEncoding pathEncoding) {
            this.pathEncoding = Objects.requireNonNull(pathEncoding, "pathEncoding cannot be null");
            return this;
        }

        /** Sets the maximum UTF-8 size allowed for inline and reference checkpoint envelopes. */
        public Builder checkpointEnvelopeLimitBytes(int checkpointEnvelopeLimitBytes) {
            if (checkpointEnvelopeLimitBytes < 1
                    || checkpointEnvelopeLimitBytes > DEFAULT_CHECKPOINT_ENVELOPE_LIMIT_BYTES) {
                throw new IllegalArgumentException("checkpointEnvelopeLimitBytes must be between 1 and "
                        + DEFAULT_CHECKPOINT_ENVELOPE_LIMIT_BYTES);
            }
            this.checkpointEnvelopeLimitBytes = checkpointEnvelopeLimitBytes;
            return this;
        }

        /** Configures built-in structured JSON preview generation. */
        public Builder previewConfig(PreviewConfig previewConfig) {
            Objects.requireNonNull(previewConfig, "previewConfig cannot be null");
            previewGenerator = (serialized, context) -> PayloadPreview.buildPreviewFromJson(serialized, previewConfig);
            return this;
        }

        public Builder previewGenerator(PayloadPreviewGenerator previewGenerator) {
            this.previewGenerator = previewGenerator;
            return this;
        }

        public FileSystemPayloadOffloader build() {
            return new FileSystemPayloadOffloader(this);
        }
    }
}
