// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to the local filesystem (an EFS mount, an S3 File Gateway path, {@code /tmp} for
 * testing, or any writable directory). Mirrors the JS {@code FileExporter}.
 *
 * <p>Two output modes:
 *
 * <ul>
 *   <li>{@link Mode#NDJSON} (default) — every record is appended as one compact JSON line to a date-partitioned file
 *       {@code {directory}/{YYYY-MM-DD}.ndjson}, where the date is the record's {@code emittedAt} day (UTC). One line
 *       per emission, so replay/suspend emissions accumulate.
 *   <li>{@link Mode#JSON} — each execution gets its own pretty-printed (2-space) file
 *       {@code {directory}/{executionName}.json}, overwritten on each update so the file always holds the latest view.
 * </ul>
 *
 * <p>File names are derived only from the record's {@code emittedAt} day (NDJSON) or a sanitized execution name/ARN
 * (JSON): every character outside {@code [a-zA-Z0-9._-]} is replaced with {@code _}, so the name is deterministic and
 * cannot escape {@code directory} via path separators or {@code ..}. The resolved path is additionally normalized and
 * verified to stay within {@code directory} as defense in depth.
 *
 * <p>The plugin applies {@link #maxRecordSizeBytes()} truncation against {@link #render(WorkflowInsightRecord)} before
 * {@link #export(WorkflowInsightRecord)} is called; the filesystem has no practical per-record limit, so there is no
 * default cap. Writes are immediate, so {@link #flush()} is a no-op.
 *
 * @see InsightExporter
 */
@Experimental
public final class FileExporter implements InsightExporter {

    /** File output mode. */
    @Experimental
    public enum Mode {
        /** Append every record as one compact JSON line to a date-partitioned {@code .ndjson} file (default). */
        NDJSON,
        /** Write one pretty-printed JSON file per execution, overwriting on update. */
        JSON
    }

    /** How operations are rendered in the written record. */
    @Experimental
    public enum OperationsFormat {
        /** The canonical {@code operations} array (default). */
        ARRAY,
        /** The {@code operationsByName} map, replacing the array. */
        BY_NAME,
        /** Both the {@code operations} array and an added {@code operationsByName} map. */
        BOTH
    }

    private final Path directory;
    private final Mode mode;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final AtomicBoolean dirCreated = new AtomicBoolean(false);

    private FileExporter(Builder b) {
        this.directory = b.directory;
        this.mode = b.mode != null ? b.mode : Mode.NDJSON;
        this.operationsFormat = b.operationsFormat != null ? b.operationsFormat : OperationsFormat.ARRAY;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Integer maxRecordSizeBytes() {
        return maxRecordSizeBytes;
    }

    @Override
    public Object render(WorkflowInsightRecord record) {
        switch (operationsFormat) {
            case BY_NAME:
                return record.toByNameWireMap();
            case BOTH:
                return record.toBothWireMap();
            case ARRAY:
            default:
                return record.toWireMap();
        }
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        ensureDir();
        Object formatted = render(record);
        try {
            if (mode == Mode.NDJSON) {
                Path file = resolveChild(datePartition(record) + ".ndjson");
                Files.write(
                        file,
                        (Json.stringify(formatted) + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } else {
                String name = record.executionName() != null ? record.executionName() : record.executionArn();
                Path file = resolveChild(sanitize(name) + ".json");
                Files.write(
                        file,
                        Json.stringifyPretty(formatted).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write insight record to " + directory, e);
        }
    }

    /** Writes are immediate; nothing is buffered. */
    @Override
    public void flush() {
        // no-op
    }

    /** UTC day of the record's {@code emittedAt}, or of "now" when the record carries no {@code emittedAt}. */
    private static String datePartition(WorkflowInsightRecord record) {
        String emittedAt = record.emittedAt();
        if (emittedAt != null && emittedAt.length() >= 10) {
            return emittedAt.substring(0, 10);
        }
        return java.time.Instant.now()
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .toString();
    }

    private void ensureDir() {
        if (dirCreated.get()) {
            return;
        }
        try {
            Files.createDirectories(directory);
            dirCreated.set(true);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create insight directory " + directory, e);
        }
    }

    /**
     * Resolves a sanitized child file name against {@code directory} and verifies the normalized result stays inside
     * {@code directory}. The name is already sanitized to {@code [a-zA-Z0-9._-]}, so this is defense in depth against
     * any future change in the naming rule.
     */
    private Path resolveChild(String fileName) {
        Path base = directory.toAbsolutePath().normalize();
        Path resolved = base.resolve(fileName).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalStateException("resolved insight file path escapes the configured directory: " + fileName);
        }
        return resolved;
    }

    /** Replaces every character outside {@code [a-zA-Z0-9._-]} with {@code _} (matches the JS {@code sanitize}). */
    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Builder for {@link FileExporter}. */
    public static final class Builder {
        private Path directory;
        private Mode mode;
        private OperationsFormat operationsFormat;
        private Integer maxRecordSizeBytes;

        /** Base directory to write files to, e.g. {@code /mnt/efs/workflow-insight} or {@code /tmp/insight}. */
        public Builder directory(String directory) {
            this.directory = directory != null ? Path.of(directory) : null;
            return this;
        }

        /** Base directory to write files to. */
        public Builder directory(Path directory) {
            this.directory = directory;
            return this;
        }

        /** File output mode; defaults to {@link Mode#NDJSON}. */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /** How operations are rendered; defaults to {@link OperationsFormat#ARRAY}. */
        public Builder operationsFormat(OperationsFormat operationsFormat) {
            this.operationsFormat = operationsFormat;
            return this;
        }

        /**
         * Max serialized record size before truncation; no default (the filesystem has no practical per-record cap).
         */
        public Builder maxRecordSizeBytes(Integer maxRecordSizeBytes) {
            this.maxRecordSizeBytes = maxRecordSizeBytes;
            return this;
        }

        public FileExporter build() {
            if (directory == null) {
                throw new IllegalArgumentException("directory is required");
            }
            if (directory.toString().isBlank()) {
                throw new IllegalArgumentException("directory must not be blank");
            }
            if (maxRecordSizeBytes != null && maxRecordSizeBytes <= 0) {
                throw new IllegalArgumentException("maxRecordSizeBytes must be positive when set");
            }
            return new FileExporter(this);
        }
    }
}
