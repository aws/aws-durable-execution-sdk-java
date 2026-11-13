// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class DistributedMapSourceTest {

    @Test
    void inline_storesItems() {
        var source = DistributedMapSource.inline(List.of("a", "b"));

        assertEquals(DistributedMapSource.SourceType.INLINE, source.sourceType());
        assertEquals(List.of("a", "b"), source.inlineItems());
        assertNull(source.inlineSerDes());
        assertNull(source.s3());
        assertNull(source.reader());
    }

    @Test
    void inline_withSerDes_storesSerDes() {
        var serDes = new JacksonSerDes();

        var source = DistributedMapSource.inline(List.of("a"), serDes);

        assertEquals(DistributedMapSource.SourceType.INLINE, source.sourceType());
        assertEquals(List.of("a"), source.inlineItems());
        assertSame(serDes, source.inlineSerDes());
    }

    @Test
    void inline_acceptsNullItem() {
        var source = DistributedMapSource.inline(java.util.Arrays.asList("a", null));
        assertEquals(java.util.Arrays.asList("a", null), source.inlineItems());
    }

    @Test
    void inline_null_throws() {
        var exception = assertThrows(IllegalArgumentException.class, () -> DistributedMapSource.inline(null));
        assertEquals("items cannot be null", exception.getMessage());
    }

    @Test
    void inline_rejectsUnorderedSet() {
        var unordered = new HashSet<>(List.of("a", "b"));

        var exception = assertThrows(IllegalArgumentException.class, () -> DistributedMapSource.inline(unordered));
        assertEquals("items must have deterministic iteration order", exception.getMessage());
    }

    @Test
    void inline_allowsLinkedHashSet() {
        var ordered = new LinkedHashSet<>(List.of("a", "b"));

        var source = DistributedMapSource.inline(ordered);

        assertEquals(List.of("a", "b"), source.inlineItems());
    }

    @Test
    void s3JsonLines_setsBucketKeyFormat() {
        DistributedMapSource<String> source = DistributedMapSource.s3JsonLines("s3://bucket/data.jsonl");

        assertEquals(DistributedMapSource.SourceType.S3, source.sourceType());
        var s3 = source.s3();
        assertEquals(DistributedMapSource.S3SourceType.OBJECT, s3.type());
        assertEquals("bucket", s3.bucket());
        assertEquals("data.jsonl", s3.objectConfig().key());
        assertEquals(DistributedMapSource.Format.JSON_LINES, s3.objectConfig().format());
        assertNull(s3.listedObjectMetadataConfig());
        assertNull(s3.listedObjectsConfig());
    }

    @Test
    void s3JsonLines_withoutKey_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapSource.s3JsonLines("s3://bucket-only"));
        assertEquals("s3JsonLines requires an S3 object key", exception.getMessage());
    }

    @Test
    void s3JsonArray_setsFormat() {
        DistributedMapSource<String> source = DistributedMapSource.s3JsonArray("s3://bucket/data.json");

        var s3 = source.s3();
        assertEquals(DistributedMapSource.S3SourceType.OBJECT, s3.type());
        assertEquals("bucket", s3.bucket());
        assertEquals("data.json", s3.objectConfig().key());
        assertEquals(DistributedMapSource.Format.JSON_ARRAY, s3.objectConfig().format());
    }

    @Test
    void s3JsonArray_withoutKey_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapSource.s3JsonArray("s3://bucket-only"));
        assertEquals("s3JsonArray requires an S3 object key", exception.getMessage());
    }

    @Test
    void s3Csv_setsFormatAndCsvFormat() {
        var format = DistributedMapSource.CsvFormat.headers(List.of("a", "b"));

        DistributedMapSource<String> source = DistributedMapSource.s3Csv("s3://bucket/data.csv", format);

        var s3 = source.s3();
        assertEquals(DistributedMapSource.S3SourceType.OBJECT, s3.type());
        assertEquals("bucket", s3.bucket());
        assertEquals("data.csv", s3.objectConfig().key());
        assertEquals(DistributedMapSource.Format.CSV, s3.objectConfig().format());
        assertSame(format, s3.objectConfig().csvFormat());
    }

    @Test
    void s3Csv_withoutKey_throws() {
        var format = DistributedMapSource.CsvFormat.firstRow();

        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapSource.s3Csv("s3://bucket-only", format));
        assertEquals("s3Csv requires an S3 object key", exception.getMessage());
    }

    @Test
    void s3Objects_setsListedObjectMetadataWithPrefix() {
        DistributedMapSource<String> source = DistributedMapSource.s3Objects("s3://bucket/data/");

        var s3 = source.s3();
        assertEquals(DistributedMapSource.S3SourceType.LISTED_OBJECT_METADATA, s3.type());
        assertEquals("bucket", s3.bucket());
        assertEquals("data/", s3.listedObjectMetadataConfig().keyPrefix());
        assertNull(s3.objectConfig());
        assertNull(s3.listedObjectsConfig());
    }

    @Test
    void s3Objects_wholeBucket_allowsEmptyPrefix() {
        DistributedMapSource<String> source = DistributedMapSource.s3Objects("s3://bucket");

        var s3 = source.s3();
        assertEquals("bucket", s3.bucket());
        assertEquals("", s3.listedObjectMetadataConfig().keyPrefix());
        assertEquals(DistributedMapSource.S3SourceType.LISTED_OBJECT_METADATA, s3.type());
    }

    @Test
    void s3FlattenedJsonLines_setsListedObjects() {
        DistributedMapSource<String> source = DistributedMapSource.s3FlattenedJsonLines("s3://bucket/prefix/");

        var s3 = source.s3();
        assertEquals(DistributedMapSource.S3SourceType.LISTED_OBJECTS, s3.type());
        assertEquals("bucket", s3.bucket());
        assertEquals("prefix/", s3.listedObjectsConfig().keyPrefix());
        assertEquals(
                DistributedMapSource.Format.JSON_LINES, s3.listedObjectsConfig().format());
        assertNull(s3.objectConfig());
    }

    @Test
    void s3FlattenedJsonArray_setsListedObjects() {
        DistributedMapSource<String> source = DistributedMapSource.s3FlattenedJsonArray("s3://bucket/prefix/");

        var s3 = source.s3();
        assertEquals(DistributedMapSource.S3SourceType.LISTED_OBJECTS, s3.type());
        assertEquals(
                DistributedMapSource.Format.JSON_ARRAY, s3.listedObjectsConfig().format());
    }

    @Test
    void s3FlattenedCsv_setsListedObjects() {
        var format = DistributedMapSource.CsvFormat.firstRow();

        DistributedMapSource<String> source = DistributedMapSource.s3FlattenedCsv("s3://bucket/prefix/", format);

        var s3 = source.s3();
        assertEquals(DistributedMapSource.S3SourceType.LISTED_OBJECTS, s3.type());
        assertEquals(DistributedMapSource.Format.CSV, s3.listedObjectsConfig().format());
        assertSame(format, s3.listedObjectsConfig().csvFormat());
    }

    @Test
    void s3FlattenedCsv_wholeBucket_allowsEmptyPrefix() {
        DistributedMapSource<String> source =
                DistributedMapSource.s3FlattenedCsv("s3://bucket", DistributedMapSource.CsvFormat.firstRow());

        assertEquals("", source.s3().listedObjectsConfig().keyPrefix());
    }

    @Test
    void reader_storesFunctionName() {
        DistributedMapSource<String> source = DistributedMapSource.reader("my-reader");

        assertEquals(DistributedMapSource.SourceType.READER_FUNCTION, source.sourceType());
        assertEquals("my-reader", source.reader().functionName());
        assertNull(source.reader().initialState());
        assertNull(source.reader().stateSerDes());
    }

    @Test
    void reader_withInitialState_storesState() {
        var serDes = new JacksonSerDes();

        DistributedMapSource<String> source = DistributedMapSource.reader("my-reader", "start-token", serDes);

        assertEquals("my-reader", source.reader().functionName());
        assertEquals("start-token", source.reader().initialState());
        assertSame(serDes, source.reader().stateSerDes());
    }

    @Test
    void reader_emptyFunctionName_throws() {
        var exception = assertThrows(IllegalArgumentException.class, () -> DistributedMapSource.reader(""));
        assertEquals("function name cannot be empty", exception.getMessage());
    }

    @Test
    void maxItems_setsMaxItems() {
        DistributedMapSource<String> source = DistributedMapSource.<String>s3JsonLines("s3://bucket/data.jsonl")
                .maxItems(500);

        assertEquals(500L, source.maxItems());
    }

    @Test
    void maxItems_zero_throws() {
        DistributedMapSource<String> source = DistributedMapSource.s3JsonLines("s3://bucket/data.jsonl");

        var exception = assertThrows(IllegalArgumentException.class, () -> source.maxItems(0));
        assertEquals("maxItems must be between 1 and 100000000, got: 0", exception.getMessage());
    }

    @Test
    void maxItems_atTheLimit_stores() {
        DistributedMapSource<String> source = DistributedMapSource.<String>s3JsonLines("s3://bucket/data.jsonl")
                .maxItems(100_000_000L);

        assertEquals(100_000_000L, source.maxItems());
    }

    @Test
    void maxItems_aboveTheLimit_throws() {
        DistributedMapSource<String> source = DistributedMapSource.s3JsonLines("s3://bucket/data.jsonl");

        var exception = assertThrows(IllegalArgumentException.class, () -> source.maxItems(100_000_001L));
        assertEquals("maxItems must be between 1 and 100000000, got: 100000001", exception.getMessage());
    }

    @Test
    void expectedBucketOwner_twelveDigits_stores() {
        DistributedMapSource<String> source = DistributedMapSource.<String>s3JsonLines("s3://bucket/data.jsonl")
                .expectedBucketOwner("123456789012");

        assertEquals("123456789012", source.s3().expectedBucketOwner());
    }

    @Test
    void expectedBucketOwner_invalid_throws() {
        DistributedMapSource<String> source = DistributedMapSource.s3JsonLines("s3://bucket/data.jsonl");

        var exception = assertThrows(IllegalArgumentException.class, () -> source.expectedBucketOwner("123"));
        assertEquals("expectedBucketOwner must be a 12-digit account id, got: 123", exception.getMessage());
    }

    @Test
    void expectedBucketOwner_onNonS3Source_throws() {
        var source = DistributedMapSource.inline(List.of("a"));

        var exception = assertThrows(IllegalStateException.class, () -> source.expectedBucketOwner("123456789012"));
        assertEquals("expectedBucketOwner only applies to S3 sources", exception.getMessage());
    }

    @Test
    void s3JsonLines_missingScheme_throws() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> DistributedMapSource.s3JsonLines("bucket/key"));
        assertEquals("S3 URI must start with s3://, got: bucket/key", exception.getMessage());
    }

    @Test
    void s3Objects_missingBucket_throws() {
        var exception = assertThrows(IllegalArgumentException.class, () -> DistributedMapSource.s3Objects("s3:///key"));
        assertEquals("S3 URI must contain a bucket, got: s3:///key", exception.getMessage());
    }

    @Test
    void expectedColumns_setsFirstRowAndColumns() {
        var format = DistributedMapSource.CsvFormat.expectedColumns(List.of("a", "b"));

        assertEquals(DistributedMapSource.CsvFormat.HeaderLocation.FIRST_ROW, format.headerLocation());
        assertEquals(List.of("a", "b"), format.columns());
        assertEquals(DistributedMapSource.CsvDelimiter.COMMA, format.delimiter());
    }

    @Test
    void headers_setsGivenAndColumns() {
        var format = DistributedMapSource.CsvFormat.headers(List.of("a", "b"));

        assertEquals(DistributedMapSource.CsvFormat.HeaderLocation.GIVEN, format.headerLocation());
        assertEquals(List.of("a", "b"), format.columns());
        assertEquals(DistributedMapSource.CsvDelimiter.COMMA, format.delimiter());
    }

    @Test
    void firstRow_hasNoColumns() {
        var format = DistributedMapSource.CsvFormat.firstRow();

        assertEquals(DistributedMapSource.CsvFormat.HeaderLocation.FIRST_ROW, format.headerLocation());
        assertNull(format.columns());
        assertEquals(DistributedMapSource.CsvDelimiter.COMMA, format.delimiter());
    }

    @Test
    void withDelimiter_setsDelimiter() {
        var format = DistributedMapSource.CsvFormat.headers(List.of("a", "b"))
                .withDelimiter(DistributedMapSource.CsvDelimiter.PIPE);

        assertEquals(DistributedMapSource.CsvDelimiter.PIPE, format.delimiter());
        assertEquals(DistributedMapSource.CsvFormat.HeaderLocation.GIVEN, format.headerLocation());
        assertEquals(List.of("a", "b"), format.columns());
    }

    @Test
    void constructor_nullDelimiter_defaultsToComma() {
        var format = new DistributedMapSource.CsvFormat(
                DistributedMapSource.CsvFormat.HeaderLocation.GIVEN, List.of("a"), null);

        assertEquals(DistributedMapSource.CsvDelimiter.COMMA, format.delimiter());
    }

    @Test
    void expectedColumns_empty_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapSource.CsvFormat.expectedColumns(List.of()));
        assertEquals("expectedColumns cannot be empty", exception.getMessage());
    }

    @Test
    void expectedColumns_aboveTheColumnLimit_throws() {
        var columns =
                java.util.stream.IntStream.range(0, 1001).mapToObj(i -> "c" + i).toList();

        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapSource.CsvFormat.expectedColumns(columns));
        assertEquals("expectedColumns must hold at most 1000 names, got: 1001", exception.getMessage());
    }

    @Test
    void headers_nameAboveTheLengthLimit_throws() {
        var columns = List.of("a".repeat(257));

        var exception =
                assertThrows(IllegalArgumentException.class, () -> DistributedMapSource.CsvFormat.headers(columns));
        assertEquals("each headers name must be at most 256 characters, got: 257", exception.getMessage());
    }

    @Test
    void expectedColumns_duplicates_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapSource.CsvFormat.expectedColumns(List.of("a", "a")));
        assertEquals("expectedColumns must not contain duplicates", exception.getMessage());
    }

    @Test
    void headers_empty_throws() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> DistributedMapSource.CsvFormat.headers(List.of()));
        assertEquals("headers cannot be empty", exception.getMessage());
    }

    @Test
    void headers_emptyName_throws() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> DistributedMapSource.CsvFormat.headers(List.of("")));
        assertEquals("each headers name cannot be empty", exception.getMessage());
    }

    @Test
    void headers_nullName_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapSource.CsvFormat.headers(Arrays.asList("a", null)));
        assertEquals("each headers name cannot be empty", exception.getMessage());
    }

    @Test
    void expectedColumns_emptyName_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapSource.CsvFormat.expectedColumns(List.of("")));
        assertEquals("each expectedColumns name cannot be empty", exception.getMessage());
    }

    @Test
    void expectedColumns_nullName_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapSource.CsvFormat.expectedColumns(Arrays.asList("a", null)));
        assertEquals("each expectedColumns name cannot be empty", exception.getMessage());
    }

    @Test
    void headers_duplicates_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> DistributedMapSource.CsvFormat.headers(List.of("a", "a")));
        assertEquals("headers must not contain duplicates", exception.getMessage());
    }

    @Test
    void constructor_givenWithoutColumns_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DistributedMapSource.CsvFormat(
                        DistributedMapSource.CsvFormat.HeaderLocation.GIVEN, null, null));
        assertEquals("CsvFormat with headerLocation GIVEN requires headers", exception.getMessage());
    }

    @Test
    void constructor_givenWithDuplicateColumns_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DistributedMapSource.CsvFormat(
                        DistributedMapSource.CsvFormat.HeaderLocation.GIVEN, List.of("a", "a"), null));
        assertEquals("headers must not contain duplicates", exception.getMessage());
    }

    @Test
    void constructor_firstRowWithEmptyColumns_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DistributedMapSource.CsvFormat(
                        DistributedMapSource.CsvFormat.HeaderLocation.FIRST_ROW, List.of(), null));
        assertEquals("expectedColumns cannot be empty", exception.getMessage());
    }

    @Test
    void csvFormat_withoutHeaderLocation_throws() {
        var exception = assertThrows(
                IllegalArgumentException.class, () -> new DistributedMapSource.CsvFormat(null, null, null));
        assertEquals("CsvFormat requires a headerLocation", exception.getMessage());
    }
}
