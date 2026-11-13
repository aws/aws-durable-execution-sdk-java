// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.DistributedMapValidation;
import software.amazon.lambda.durable.util.ParameterValidator;

/**
 * Source of items for a distributed map run.
 *
 * @param <I> the item type produced by the source
 */
public class DistributedMapSource<I> {

    private static final long MAX_ITEMS_TO_READ = 100_000_000L;

    /** The kind of source backing a distributed map. */
    public enum SourceType {
        INLINE,
        S3,
        READER_FUNCTION
    }

    /** How the bytes of an S3 object are split into items. */
    public enum Format {
        JSON_LINES,
        JSON_ARRAY,
        CSV
    }

    /** What an S3 source reads. */
    public enum S3SourceType {
        OBJECT,
        LISTED_OBJECT_METADATA,
        LISTED_OBJECTS
    }

    private final SourceType sourceType;
    private final Long maxItems;
    private final List<I> inlineItems;
    private final SerDes inlineSerDes;
    private final S3SourceConfig s3;
    private final ReaderSourceConfig reader;

    private DistributedMapSource(
            SourceType sourceType,
            Long maxItems,
            List<I> inlineItems,
            SerDes inlineSerDes,
            S3SourceConfig s3,
            ReaderSourceConfig reader) {
        if (maxItems != null && (maxItems < 1 || maxItems > MAX_ITEMS_TO_READ)) {
            throw new IllegalArgumentException(
                    "maxItems must be between 1 and " + MAX_ITEMS_TO_READ + ", got: " + maxItems);
        }
        this.sourceType = sourceType;
        this.maxItems = maxItems;
        // A null item is allowed and is sent as JSON null, so the copy must accept null elements.
        this.inlineItems = inlineItems != null ? Collections.unmodifiableList(new ArrayList<>(inlineItems)) : null;
        this.inlineSerDes = inlineSerDes;
        this.s3 = s3;
        this.reader = reader;
    }

    public SourceType sourceType() {
        return sourceType;
    }

    public Long maxItems() {
        return maxItems;
    }

    public List<I> inlineItems() {
        return inlineItems;
    }

    public SerDes inlineSerDes() {
        return inlineSerDes;
    }

    public S3SourceConfig s3() {
        return s3;
    }

    public ReaderSourceConfig reader() {
        return reader;
    }

    /** An in-memory list of items embedded in the start checkpoint. */
    public static <I> DistributedMapSource<I> inline(Collection<I> items) {
        return inline(items, null);
    }

    /** An in-memory list of items embedded in the start checkpoint, serialized with the given SerDes. */
    public static <I> DistributedMapSource<I> inline(Collection<I> items, SerDes serDes) {
        ParameterValidator.validateOrderedCollection(items);
        return new DistributedMapSource<>(SourceType.INLINE, null, new ArrayList<>(items), serDes, null, null);
    }

    /** Read a single S3 object, treating each line as an item. */
    public static <I> DistributedMapSource<I> s3JsonLines(String uri) {
        var parsed = DistributedMapValidation.parseS3Uri(uri);
        if (parsed.path() == null) {
            throw new IllegalArgumentException("s3JsonLines requires an S3 object key");
        }
        return s3(object(parsed.bucket(), parsed.path(), Format.JSON_LINES, null));
    }

    /** Read a single S3 object holding a JSON array, treating each element as an item. */
    public static <I> DistributedMapSource<I> s3JsonArray(String uri) {
        var parsed = DistributedMapValidation.parseS3Uri(uri);
        if (parsed.path() == null) {
            throw new IllegalArgumentException("s3JsonArray requires an S3 object key");
        }
        return s3(object(parsed.bucket(), parsed.path(), Format.JSON_ARRAY, null));
    }

    /** Read a single S3 object, treating each record as an item. */
    public static <I> DistributedMapSource<I> s3Csv(String uri, CsvFormat format) {
        var parsed = DistributedMapValidation.parseS3Uri(uri);
        if (parsed.path() == null) {
            throw new IllegalArgumentException("s3Csv requires an S3 object key");
        }
        return s3(object(parsed.bucket(), parsed.path(), Format.CSV, format));
    }

    /** Read each object under a prefix as one item (object contents are not read). */
    public static <I> DistributedMapSource<I> s3Objects(String prefixUri) {
        var parsed = DistributedMapValidation.parseS3Uri(prefixUri);
        return s3(new S3SourceConfig(
                S3SourceType.LISTED_OBJECT_METADATA,
                parsed.bucket(),
                null,
                null,
                new ListedObjectMetadataConfig(prefixOrEmpty(parsed.path())),
                null));
    }

    /** Read a prefix, flattening each object's lines into items. */
    public static <I> DistributedMapSource<I> s3FlattenedJsonLines(String prefixUri) {
        var parsed = DistributedMapValidation.parseS3Uri(prefixUri);
        return s3(listedObjects(parsed.bucket(), prefixOrEmpty(parsed.path()), Format.JSON_LINES, null));
    }

    /** Read a prefix, flattening each object's JSON array elements into items. */
    public static <I> DistributedMapSource<I> s3FlattenedJsonArray(String prefixUri) {
        var parsed = DistributedMapValidation.parseS3Uri(prefixUri);
        return s3(listedObjects(parsed.bucket(), prefixOrEmpty(parsed.path()), Format.JSON_ARRAY, null));
    }

    /** Read a prefix, flattening each object's records into items. */
    public static <I> DistributedMapSource<I> s3FlattenedCsv(String prefixUri, CsvFormat format) {
        var parsed = DistributedMapValidation.parseS3Uri(prefixUri);
        return s3(listedObjects(parsed.bucket(), prefixOrEmpty(parsed.path()), Format.CSV, format));
    }

    private static S3SourceConfig object(String bucket, String key, Format format, CsvFormat csvFormat) {
        return new S3SourceConfig(
                S3SourceType.OBJECT, bucket, null, new ObjectConfig(key, format, csvFormat), null, null);
    }

    private static S3SourceConfig listedObjects(String bucket, String keyPrefix, Format format, CsvFormat csvFormat) {
        return new S3SourceConfig(
                S3SourceType.LISTED_OBJECTS,
                bucket,
                null,
                null,
                null,
                new ListedObjectsConfig(keyPrefix, format, csvFormat));
    }

    /** Page items from a customer-supplied reader Lambda function. */
    public static <I> DistributedMapSource<I> reader(String functionName) {
        return reader(functionName, null, null);
    }

    /** Page items from a customer-supplied reader Lambda function with an initial state. */
    public static <I, S> DistributedMapSource<I> reader(String functionName, S initialState, SerDes stateSerDes) {
        return new DistributedMapSource<>(
                SourceType.READER_FUNCTION,
                null,
                null,
                null,
                null,
                new ReaderSourceConfig(functionName, initialState, stateSerDes));
    }

    /** Returns a copy limiting the total number of items read. */
    public DistributedMapSource<I> maxItems(long maxItems) {
        return new DistributedMapSource<>(sourceType, maxItems, inlineItems, inlineSerDes, s3, reader);
    }

    /** Returns a copy asserting the expected S3 bucket owner (S3 sources only). */
    public DistributedMapSource<I> expectedBucketOwner(String accountId) {
        if (s3 == null) {
            throw new IllegalStateException("expectedBucketOwner only applies to S3 sources");
        }
        DistributedMapValidation.validateBucketOwner(accountId);
        return new DistributedMapSource<>(
                sourceType, maxItems, inlineItems, inlineSerDes, s3.withExpectedBucketOwner(accountId), reader);
    }

    private static <I> DistributedMapSource<I> s3(S3SourceConfig s3) {
        return new DistributedMapSource<>(SourceType.S3, null, null, null, s3, null);
    }

    private static String prefixOrEmpty(String path) {
        return path != null ? path : "";
    }

    /** Resolved S3 source configuration. Exactly one config block matching {@code type} is set. */
    public record S3SourceConfig(
            S3SourceType type,
            String bucket,
            String expectedBucketOwner,
            ObjectConfig objectConfig,
            ListedObjectMetadataConfig listedObjectMetadataConfig,
            ListedObjectsConfig listedObjectsConfig) {

        S3SourceConfig withExpectedBucketOwner(String owner) {
            return new S3SourceConfig(
                    type, bucket, owner, objectConfig, listedObjectMetadataConfig, listedObjectsConfig);
        }
    }

    /** Reads the single object at {@code key}. */
    public record ObjectConfig(String key, Format format, CsvFormat csvFormat) {}

    /** Lists the objects under {@code keyPrefix} without reading their contents. */
    public record ListedObjectMetadataConfig(String keyPrefix) {}

    /** Lists the objects under {@code keyPrefix} and reads every listed object as {@code format}. */
    public record ListedObjectsConfig(String keyPrefix, Format format, CsvFormat csvFormat) {}

    /** Resolved reader-function source configuration. */
    public record ReaderSourceConfig(String functionName, Object initialState, SerDes stateSerDes) {
        public ReaderSourceConfig {
            DistributedMapValidation.validateFunctionName(functionName);
        }
    }

    /** CSV parsing options for a distributed map S3 source. */
    public record CsvFormat(HeaderLocation headerLocation, List<String> columns, CsvDelimiter delimiter) {
        private static final int MAX_CSV_COLUMNS = 1000;
        private static final int MAX_CSV_COLUMN_LENGTH = 256;

        /** Where the column header row comes from. */
        public enum HeaderLocation {
            FIRST_ROW,
            GIVEN
        }

        public CsvFormat {
            if (headerLocation == null) {
                throw new IllegalArgumentException("CsvFormat requires a headerLocation");
            }
            if (headerLocation == HeaderLocation.GIVEN) {
                if (columns == null) {
                    throw new IllegalArgumentException("CsvFormat with headerLocation GIVEN requires headers");
                }
                validateColumns("headers", columns);
            } else {
                validateColumns("expectedColumns", columns);
            }
            columns = columns != null ? List.copyOf(columns) : null;
            delimiter = delimiter != null ? delimiter : CsvDelimiter.COMMA;
        }

        /**
         * File has its own header row. Column names are used only for compile-time typing and are not sent on the wire.
         */
        public static CsvFormat expectedColumns(List<String> columns) {
            return new CsvFormat(HeaderLocation.FIRST_ROW, columns, CsvDelimiter.COMMA);
        }

        /** File has no header row. The given names are the columns, sent on the wire, and the first row is data. */
        public static CsvFormat headers(List<String> headers) {
            return new CsvFormat(HeaderLocation.GIVEN, headers, CsvDelimiter.COMMA);
        }

        /** File has its own header row and no column names are declared. */
        public static CsvFormat firstRow() {
            return new CsvFormat(HeaderLocation.FIRST_ROW, null, CsvDelimiter.COMMA);
        }

        /** Returns a copy with the given delimiter. */
        public CsvFormat withDelimiter(CsvDelimiter delimiter) {
            return new CsvFormat(headerLocation, columns, delimiter);
        }

        private static void validateColumns(String parameterName, List<String> columns) {
            if (columns == null) {
                return;
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException(parameterName + " cannot be empty");
            }
            if (columns.size() > MAX_CSV_COLUMNS) {
                throw new IllegalArgumentException(
                        parameterName + " must hold at most " + MAX_CSV_COLUMNS + " names, got: " + columns.size());
            }
            for (var column : columns) {
                if (column == null || column.isEmpty()) {
                    throw new IllegalArgumentException("each " + parameterName + " name cannot be empty");
                }
                if (column.length() > MAX_CSV_COLUMN_LENGTH) {
                    throw new IllegalArgumentException("each " + parameterName + " name must be at most "
                            + MAX_CSV_COLUMN_LENGTH + " characters, got: " + column.length());
                }
            }
            if (new HashSet<>(columns).size() != columns.size()) {
                throw new IllegalArgumentException(parameterName + " must not contain duplicates");
            }
        }
    }

    /** Delimiter for CSV distributed map sources. */
    public enum CsvDelimiter {
        COMMA,
        PIPE,
        SEMICOLON,
        SPACE,
        TAB
    }
}
