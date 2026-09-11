// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to a directory: appended as one JSON line per record to a date-named NDJSON file
 * (default), or written as one pretty-printed JSON file per execution that later exports overwrite. Suitable for an EFS
 * mount or {@code /tmp}. No truncation limit by default.
 */
@Experimental
public final class FileExporter implements InsightExporter {

    /** File layout. */
    @Experimental
    public enum Mode {
        /** Append every record to {@code {directory}/{YYYY-MM-DD}.ndjson}. */
        NDJSON("ndjson"),
        /** Write {@code {directory}/{executionName}.json}, overwriting on each export. */
        JSON("json");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        /** The configuration string for this mode. */
        public String value() {
            return value;
        }

        /** Parses a configuration string; unknown values are rejected. */
        public static Mode fromValue(String value) {
            for (Mode m : values()) {
                if (m.value.equals(value)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("Unknown mode: \"" + value + "\". Expected ndjson or json.");
        }
    }

    private final Path directory;
    private final Mode mode;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final Object appendLock = new Object();
    private volatile boolean directoryCreated;

    private FileExporter(Builder b) {
        this.directory = Path.of(requireNonNull(b.directory, "directory"));
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
        return operationsFormat.apply(record);
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        Map<String, Object> formatted = operationsFormat.apply(record);
        try {
            ensureDirectory();
            if (mode == Mode.NDJSON) {
                String date = ((String) formatted.get("emittedAt")).substring(0, 10);
                byte[] line = (Json.stringify(formatted) + "\n").getBytes(StandardCharsets.UTF_8);
                // Appends are written in chunks, so concurrent exports (child-context branches) could interleave lines
                // longer than one chunk. Hold a lock for the whole record so each line lands intact.
                synchronized (appendLock) {
                    Files.write(
                            directory.resolve(date + ".ndjson"),
                            line,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                }
            } else {
                String name = record.executionName() != null ? record.executionName() : record.executionArn();
                Files.write(
                        directory.resolve(sanitize(name) + ".json"),
                        Json.prettyStringify(formatted).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("FileExporter: write to " + directory + " failed", e);
        }
    }

    private void ensureDirectory() throws IOException {
        if (!directoryCreated) {
            Files.createDirectories(directory);
            directoryCreated = true;
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Builder for {@link FileExporter}. */
    public static final class Builder {
        private String directory;
        private Mode mode;
        private OperationsFormat operationsFormat;
        private Integer maxRecordSizeBytes;

        /** Base directory, for example {@code /mnt/efs/workflow-insight} or {@code /tmp/insight}. */
        public Builder directory(String directory) {
            this.directory = directory;
            return this;
        }

        /** Default {@code NDJSON}. */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder operationsFormat(OperationsFormat operationsFormat) {
            this.operationsFormat = operationsFormat;
            return this;
        }

        /** No default: the filesystem has no practical per-record limit. */
        public Builder maxRecordSizeBytes(Integer maxRecordSizeBytes) {
            this.maxRecordSizeBytes = maxRecordSizeBytes;
            return this;
        }

        public FileExporter build() {
            return new FileExporter(this);
        }
    }
}
