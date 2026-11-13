// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.DistributedMapDetails;
import software.amazon.awssdk.services.lambda.model.DistributedMapOptions;
import software.amazon.awssdk.services.lambda.model.DistributedMapResultItem;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.DistributedMapConfig;
import software.amazon.lambda.durable.config.DistributedMapDestination;
import software.amazon.lambda.durable.config.DistributedMapProcessor;
import software.amazon.lambda.durable.config.DistributedMapProcessorRetryConfig;
import software.amazon.lambda.durable.config.DistributedMapSource;
import software.amazon.lambda.durable.exception.DurableExecutionException;
import software.amazon.lambda.durable.model.DistributedMapResult;
import software.amazon.lambda.durable.model.DistributedMapStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/** Translation tests for DistributedMapOperation toOptions, toSummary and toResult. */
class DistributedMapOperationTranslationTest {

    private static final SerDes SERDES = new JacksonSerDes();

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static DistributedMapOptions options(
            DistributedMapSource<?> source,
            DistributedMapProcessor processor,
            DistributedMapConfig config,
            boolean collect) {
        return DistributedMapOperation.toOptions(source, processor, 4, config, SERDES, collect);
    }

    private static DistributedMapOptions options(DistributedMapSource<?> source, DistributedMapProcessor processor) {
        return options(source, processor, DistributedMapConfig.builder().build(), false);
    }

    private static DistributedMapProcessor batchProcessor() {
        return DistributedMapProcessor.reportBatchOutcome("proc").build();
    }

    private static DistributedMapDetails.Builder detailsBuilder() {
        return DistributedMapDetails.builder()
                .sourceExhausted(true)
                .successCount(1L)
                .failureCount(0L)
                .readCount(1L)
                .totalCount(1L);
    }

    private static DistributedMapResultItem succeededItem(String itemId, String output) {
        return DistributedMapResultItem.builder()
                .itemId(itemId)
                .status("SUCCEEDED")
                .output(output)
                .build();
    }

    private static DistributedMapResultItem failedItem(String itemId, ErrorObject error) {
        return DistributedMapResultItem.builder()
                .itemId(itemId)
                .status("FAILED")
                .error(error)
                .build();
    }

    /** Serdes that upper-cases on serialize and upper-cases plus counts on deserialize. */
    private static final class UpperSerDes implements SerDes {
        int deserializeCount = 0;

        @Override
        public String serialize(Object value) {
            return String.valueOf(value).toUpperCase(Locale.ROOT);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            deserializeCount++;
            return (T) data.toUpperCase(Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------
    // toOptions - top level
    // ------------------------------------------------------------------

    @Test
    void inlineSourceSerializesItemsThroughDefaultSerdes() {
        var options = options(DistributedMapSource.inline(List.of("a", "b")), batchProcessor());
        assertEquals(4, options.maxConcurrency().intValue());
        assertEquals("INLINE", options.source().typeAsString());
        assertEquals(
                List.of(SERDES.serialize("a"), SERDES.serialize("b")),
                options.source().inlineSourceConfig().items());
    }

    @Test
    void inlineSourceSendsNullItemAsJsonNull() {
        var options = options(DistributedMapSource.inline(java.util.Arrays.asList("a", null)), batchProcessor());
        assertEquals(
                List.of(SERDES.serialize("a"), "null"),
                options.source().inlineSourceConfig().items());
    }

    @Test
    void inlineSourceRejectsItemSerializedToNull() {
        SerDes nullSerDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return null;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return null;
            }
        };
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> options(DistributedMapSource.inline(List.of("a"), nullSerDes), batchProcessor()));
        assertEquals("serialization of inline item 0 produced no string", exception.getMessage());
    }

    /** One item of "é" characters, padded with "a", whose serialized inline array is exactly totalBytes. */
    private static List<String> inlineItemsOfSerializedSize(int totalBytes) {
        // The serdes wraps the item in quotes. The array escapes those quotes, then adds its own quotes and brackets.
        // So the array is 8 bytes larger than the item text. Each "é" is 2 UTF-8 bytes.
        int bodyBytes = totalBytes - 8;
        var body = (bodyBytes % 2 == 1 ? "a" : "") + "é".repeat(bodyBytes / 2);
        return List.of(body);
    }

    private static int serializedInlineBytes(List<String> items) {
        var bodies = items.stream().map(SERDES::serialize).toList();
        return SERDES.serialize(bodies).getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void inlineSourceAcceptsSerializedSizeAtTheLimit() {
        var items = inlineItemsOfSerializedSize(1024 * 1024);
        assertEquals(1024 * 1024, serializedInlineBytes(items));

        var options = options(DistributedMapSource.inline(items), batchProcessor());
        assertEquals(
                List.of(SERDES.serialize(items.get(0))),
                options.source().inlineSourceConfig().items());
    }

    @Test
    void inlineSourceRejectsSerializedSizeOneByteOverTheLimit() {
        var items = inlineItemsOfSerializedSize(1024 * 1024 + 1);
        assertEquals(1024 * 1024 + 1, serializedInlineBytes(items));
        // The character count is below the limit, so only a byte count rejects this source.
        assertTrue(SERDES.serialize(List.of(SERDES.serialize(items.get(0)))).length() < 1024 * 1024);

        var exception = assertThrows(
                IllegalArgumentException.class, () -> options(DistributedMapSource.inline(items), batchProcessor()));
        assertEquals("inline source exceeds the 1 MB limit (serialized size: 1048577 bytes)", exception.getMessage());
    }

    @Test
    void inlineSourceUsesCustomSerdesForEachItem() {
        var options = options(DistributedMapSource.inline(List.of("a", "b"), new UpperSerDes()), batchProcessor());
        assertEquals(List.of("A", "B"), options.source().inlineSourceConfig().items());
    }

    @Test
    void maxConcurrencyPassedThrough() {
        var options = DistributedMapOperation.toOptions(
                DistributedMapSource.inline(List.of("a")),
                batchProcessor(),
                42,
                DistributedMapConfig.builder().build(),
                SERDES,
                false);
        assertEquals(42, options.maxConcurrency().intValue());
    }

    @Test
    void timeoutMapsToWholeSeconds() {
        var config =
                DistributedMapConfig.builder().timeout(Duration.ofMinutes(30)).build();
        var options = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false);
        assertEquals(1800, options.timeoutSeconds().intValue());
    }

    @Test
    void timeoutOmittedWhenAbsent() {
        var options = options(DistributedMapSource.inline(List.of("a")), batchProcessor());
        assertNull(options.timeoutSeconds());
    }

    // ------------------------------------------------------------------
    // toOptions - processor
    // ------------------------------------------------------------------

    @Test
    void itemOutcomesSetsResponseTypeAndBatchSize() {
        var processor =
                DistributedMapProcessor.reportItemOutcomes("proc").batchSize(25).build();
        var wire = options(DistributedMapSource.inline(List.of("a")), processor).processor();
        assertEquals("proc", wire.functionName());
        assertEquals(List.of("REPORT_BATCH_ITEM_OUTCOMES"), wire.functionResponseTypesAsStrings());
        assertEquals(25, wire.batchSize().intValue());
    }

    @Test
    void batchOmitsResponseTypes() {
        var wire = options(DistributedMapSource.inline(List.of("a")), batchProcessor())
                .processor();
        assertFalse(wire.hasFunctionResponseTypes());
    }

    @Test
    void unlimitedRetriesMapToNegativeOne() {
        var processor = DistributedMapProcessor.reportItemOutcomes("proc")
                .retry(DistributedMapProcessorRetryConfig.builder()
                        .maxRetryAttempts(DistributedMapProcessorRetryConfig.UNLIMITED)
                        .maxRetryDuration(Duration.ofHours(1))
                        .build())
                .build();
        var wire = options(DistributedMapSource.inline(List.of("a")), processor).processor();
        assertEquals(-1, wire.maxRetryAttempts().intValue());
        assertEquals(3600, wire.maxRetryDurationSeconds().intValue());
    }

    @Test
    void explicitRetryAttemptsPassThrough() {
        var processor = DistributedMapProcessor.reportBatchOutcome("proc")
                .retry(DistributedMapProcessorRetryConfig.builder()
                        .maxRetryAttempts(0)
                        .build())
                .build();
        var wire = options(DistributedMapSource.inline(List.of("a")), processor).processor();
        assertEquals(0, wire.maxRetryAttempts().intValue());
    }

    @Test
    void retryDurationOnlyOmitsAttempts() {
        var processor = DistributedMapProcessor.reportBatchOutcome("proc")
                .retry(DistributedMapProcessorRetryConfig.builder()
                        .maxRetryDuration(Duration.ofMinutes(5))
                        .build())
                .build();
        var wire = options(DistributedMapSource.inline(List.of("a")), processor).processor();
        assertEquals(300, wire.maxRetryDurationSeconds().intValue());
        assertNull(wire.maxRetryAttempts());
    }

    @Test
    void omittedRetryConfigSendsNeitherRetryField() {
        var processor = DistributedMapProcessor.reportBatchOutcome("proc").build();
        var wire = options(DistributedMapSource.inline(List.of("a")), processor).processor();
        assertNull(wire.maxRetryAttempts());
        assertNull(wire.maxRetryDurationSeconds());
    }

    @Test
    void durableExecutionNamePrefixSent() {
        var processor = DistributedMapProcessor.reportBatchOutcome("proc")
                .durableExecutionNamePrefix("pfx")
                .build();
        var wire = options(DistributedMapSource.inline(List.of("a")), processor).processor();
        assertEquals("pfx", wire.durableExecutionNamePrefix());
    }

    // ------------------------------------------------------------------
    // toOptions - source variants
    // ------------------------------------------------------------------

    @Test
    void s3JsonLinesSourceSerializesConfig() {
        var source = DistributedMapSource.s3JsonLines("s3://bucket/data.jsonl").maxItems(500);
        var wire = options(source, batchProcessor()).source();
        assertEquals("S3", wire.typeAsString());
        assertEquals(500L, wire.maxItemsToRead().longValue());
        assertEquals("OBJECT", wire.s3SourceConfig().typeAsString());
        assertEquals("bucket", wire.s3SourceConfig().bucket());
        assertEquals("data.jsonl", wire.s3SourceConfig().objectConfig().key());
        assertEquals("JSON_LINES", wire.s3SourceConfig().objectConfig().formatAsString());
    }

    @Test
    void s3ObjectsSourceUsesListedObjectMetadataAndPrefix() {
        var wire = options(DistributedMapSource.s3Objects("s3://b/prefix/"), batchProcessor())
                .source();
        var s3 = wire.s3SourceConfig();
        assertEquals("LISTED_OBJECT_METADATA", s3.typeAsString());
        assertEquals("prefix/", s3.listedObjectMetadataConfig().keyPrefix());
        assertNull(s3.objectConfig());
        assertNull(s3.listedObjectsConfig());
    }

    @Test
    void s3FlattenedJsonLinesUsesListedObjects() {
        var s3 = options(DistributedMapSource.s3FlattenedJsonLines("s3://b/prefix/"), batchProcessor())
                .source()
                .s3SourceConfig();
        assertEquals("LISTED_OBJECTS", s3.typeAsString());
        assertEquals("prefix/", s3.listedObjectsConfig().keyPrefix());
        assertEquals("JSON_LINES", s3.listedObjectsConfig().formatAsString());
        assertNull(s3.objectConfig());
    }

    @Test
    void s3FlattenedCsvUsesListedObjectsAndCsvOptions() {
        var source = DistributedMapSource.s3FlattenedCsv(
                "s3://b/prefix", DistributedMapSource.CsvFormat.headers(List.of("a", "b")));
        var listed = options(source, batchProcessor()).source().s3SourceConfig().listedObjectsConfig();
        assertEquals("CSV", listed.formatAsString());
        assertEquals("GIVEN", listed.csvFormatOptions().headerLocationAsString());
        assertEquals(List.of("a", "b"), listed.csvFormatOptions().headers());
    }

    @Test
    void s3ExpectedBucketOwnerSent() {
        var source = DistributedMapSource.s3JsonLines("s3://b/k.jsonl").expectedBucketOwner("123456789012");
        var s3 = options(source, batchProcessor()).source().s3SourceConfig();
        assertEquals("123456789012", s3.expectedBucketOwner());
    }

    @Test
    void readerSourceSerializesInitialState() {
        var source = DistributedMapSource.reader("reader", Map.of("page", 0), null);
        var reader = options(source, batchProcessor()).source().readerFunctionSourceConfig();
        assertEquals("reader", reader.functionName());
        assertEquals(SERDES.serialize(Map.of("page", 0)), reader.initialState());
    }

    @Test
    void readerSourceRejectsInitialStateSerializedToNull() {
        SerDes nullSerDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return null;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return null;
            }
        };
        var source = DistributedMapSource.reader("reader", Map.of("page", 0), nullSerDes);
        var exception = assertThrows(IllegalArgumentException.class, () -> options(source, batchProcessor()));
        assertEquals("serialization of reader initialState produced no string", exception.getMessage());
    }

    @Test
    void readerSourceWithoutInitialStateOmitsState() {
        var reader = options(DistributedMapSource.reader("reader"), batchProcessor())
                .source()
                .readerFunctionSourceConfig();
        assertEquals("reader", reader.functionName());
        assertNull(reader.initialState());
    }

    // ------------------------------------------------------------------
    // toOptions - CSV header handling
    // ------------------------------------------------------------------

    @Test
    void csvHeadersMapToGivenAndAreSent() {
        var source = DistributedMapSource.s3Csv(
                "s3://b/data.csv", DistributedMapSource.CsvFormat.headers(List.of("a", "b")));
        var csv = options(source, batchProcessor())
                .source()
                .s3SourceConfig()
                .objectConfig()
                .csvFormatOptions();
        assertEquals("GIVEN", csv.headerLocationAsString());
        assertEquals(List.of("a", "b"), csv.headers());
        assertEquals("COMMA", csv.delimiterAsString());
    }

    @Test
    void csvFirstRowOmitsHeaders() {
        var source = DistributedMapSource.s3Csv("s3://b/data.csv", DistributedMapSource.CsvFormat.firstRow());
        var csv = options(source, batchProcessor())
                .source()
                .s3SourceConfig()
                .objectConfig()
                .csvFormatOptions();
        assertEquals("FIRST_ROW", csv.headerLocationAsString());
        assertFalse(csv.hasHeaders());
        assertEquals("COMMA", csv.delimiterAsString());
    }

    @Test
    void csvExpectedColumnsStayClientSide() {
        var source = DistributedMapSource.s3Csv(
                "s3://b/data.csv", DistributedMapSource.CsvFormat.expectedColumns(List.of("a", "b")));
        var csv = options(source, batchProcessor())
                .source()
                .s3SourceConfig()
                .objectConfig()
                .csvFormatOptions();
        assertEquals("FIRST_ROW", csv.headerLocationAsString());
        assertFalse(csv.hasHeaders());
    }

    @Test
    void csvDelimiterMapsToWireValue() {
        var source = DistributedMapSource.s3Csv(
                "s3://b/data.csv",
                DistributedMapSource.CsvFormat.headers(List.of("a"))
                        .withDelimiter(DistributedMapSource.CsvDelimiter.PIPE));
        var csv = options(source, batchProcessor())
                .source()
                .s3SourceConfig()
                .objectConfig()
                .csvFormatOptions();
        assertEquals("PIPE", csv.delimiterAsString());
    }

    // ------------------------------------------------------------------
    // toOptions - completion config
    // ------------------------------------------------------------------

    @Test
    void completionPercentageAndSampleSizeSerialize() {
        var config = DistributedMapConfig.builder()
                .completionConfig(DistributedMapConfig.CompletionConfig.failurePercentage(5, 200))
                .build();
        var completion = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .completionConfig();
        assertEquals(5.0f, completion.toleratedFailurePercentage(), 0.0001f);
        assertEquals(200, completion.minimumSampleSize().intValue());
        assertNull(completion.toleratedFailureCount());
    }

    @Test
    void completionFractionalPercentageSerializesUnchanged() {
        var config = DistributedMapConfig.builder()
                .completionConfig(DistributedMapConfig.CompletionConfig.failurePercentage(12.5))
                .build();
        var completion = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .completionConfig();
        assertEquals(12.5f, completion.toleratedFailurePercentage());
    }

    @Test
    void completionFailureCountSerializes() {
        var config = DistributedMapConfig.builder()
                .completionConfig(DistributedMapConfig.CompletionConfig.failureCount(2))
                .build();
        var completion = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .completionConfig();
        assertEquals(2, completion.toleratedFailureCount().intValue());
    }

    // ------------------------------------------------------------------
    // toOptions - destinations
    // ------------------------------------------------------------------

    @Test
    void destinationBothSuccessAndFailureSerialize() {
        var config = DistributedMapConfig.builder()
                .destination(DistributedMapDestination.of(
                        new DistributedMapDestination.Success("out", "ok", false, true, null),
                        new DistributedMapDestination.Failure("out", "bad", true, true, null)))
                .build();
        var destination = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .destination();
        var onSuccess = destination.onSuccess();
        assertEquals("S3", onSuccess.typeAsString());
        assertEquals(List.of("OUTPUT"), onSuccess.includeAsStrings());
        assertEquals("out", onSuccess.s3DestinationConfig().bucket());
        assertEquals("ok", onSuccess.s3DestinationConfig().keyPrefix());
        var onFailure = destination.onFailure();
        assertEquals(List.of("INPUT", "ERROR"), onFailure.includeAsStrings());
        assertEquals("bad", onFailure.s3DestinationConfig().keyPrefix());
    }

    @Test
    void destinationOnlySuccessOmitsFailure() {
        var config = DistributedMapConfig.builder()
                .destination(DistributedMapDestination.of(
                        new DistributedMapDestination.Success("out", "ok", false, true, null)))
                .build();
        var destination = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .destination();
        assertNotNull(destination.onSuccess());
        assertNull(destination.onFailure());
    }

    @Test
    void destinationOnlyFailureOmitsSuccess() {
        var config = DistributedMapConfig.builder()
                .destination(DistributedMapDestination.of(
                        new DistributedMapDestination.Failure("out", "bad", false, true, null)))
                .build();
        var destination = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .destination();
        assertNotNull(destination.onFailure());
        assertNull(destination.onSuccess());
    }

    @Test
    void allNullDestinationOmittedFromWire() {
        var config = DistributedMapConfig.builder()
                .destination(new DistributedMapDestination(null, null))
                .build();
        var options = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false);
        assertNull(options.destination());
    }

    @Test
    void successDestinationIncludeInputAndOutputWithOwner() {
        var config = DistributedMapConfig.builder()
                .destination(DistributedMapDestination.of(
                        new DistributedMapDestination.Success("out", "ok", true, true, "123456789012")))
                .build();
        var onSuccess = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .destination()
                .onSuccess();
        assertEquals(List.of("INPUT", "OUTPUT"), onSuccess.includeAsStrings());
        assertEquals("123456789012", onSuccess.s3DestinationConfig().expectedBucketOwner());
    }

    @Test
    void successDestinationInputOnly() {
        var config = DistributedMapConfig.builder()
                .destination(DistributedMapDestination.of(
                        new DistributedMapDestination.Success("out", "ok", true, false, null)))
                .build();
        var onSuccess = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .destination()
                .onSuccess();
        assertEquals(List.of("INPUT"), onSuccess.includeAsStrings());
    }

    @Test
    void failureDestinationErrorOnly() {
        var config = DistributedMapConfig.builder()
                .destination(DistributedMapDestination.of(
                        new DistributedMapDestination.Failure("out", "bad", false, true, "123456789012")))
                .build();
        var onFailure = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .destination()
                .onFailure();
        assertEquals(List.of("ERROR"), onFailure.includeAsStrings());
        assertEquals("123456789012", onFailure.s3DestinationConfig().expectedBucketOwner());
    }

    @Test
    void failureDestinationInputOnly() {
        var config = DistributedMapConfig.builder()
                .destination(DistributedMapDestination.of(
                        new DistributedMapDestination.Failure("out", "bad", true, false, null)))
                .build();
        var onFailure = options(DistributedMapSource.inline(List.of("a")), batchProcessor(), config, false)
                .destination()
                .onFailure();
        assertEquals(List.of("INPUT"), onFailure.includeAsStrings());
    }

    // ------------------------------------------------------------------
    // toOptions - result collection
    // ------------------------------------------------------------------

    @Test
    void resultCollectionInlineWhenCollecting() {
        var options = options(
                DistributedMapSource.inline(List.of("a")),
                batchProcessor(),
                DistributedMapConfig.builder().build(),
                true);
        assertEquals("INLINE", options.resultCollection().modeAsString());
    }

    @Test
    void resultCollectionOmittedWhenNotCollecting() {
        var options = options(DistributedMapSource.inline(List.of("a")), batchProcessor());
        assertNull(options.resultCollection());
    }

    // ------------------------------------------------------------------
    // toSummary
    // ------------------------------------------------------------------

    @Test
    void toSummaryMapsDetailFields() {
        var details = detailsBuilder()
                .successCount(5L)
                .totalCount(5L)
                .distributedMapRunArn("arn:aws:lambda:us-east-1:123456789012:map-run:abc")
                .build();
        var summary = DistributedMapOperation.toSummary(DistributedMapStatus.SUCCEEDED, details);
        assertEquals(DistributedMapStatus.SUCCEEDED, summary.status());
        assertTrue(summary.sourceExhausted());
        assertEquals(5L, summary.successCount());
        assertEquals(5L, summary.totalCount().longValue());
        assertEquals("arn:aws:lambda:us-east-1:123456789012:map-run:abc", summary.distributedMapRunArn());
    }

    @Test
    void toSummaryCarriesTheRunError() {
        var details = detailsBuilder()
                .sourceExhausted(false)
                .error(ErrorObject.builder()
                        .errorType("SourceFailed")
                        .errorMessage("bad object")
                        .errorData("{}")
                        .stackTrace(List.of("frame"))
                        .build())
                .build();
        var summary = DistributedMapOperation.toSummary(DistributedMapStatus.FAILED, details);
        assertFalse(summary.sourceExhausted());
        assertEquals("SourceFailed", summary.error().errorType());
        assertEquals("bad object", summary.error().errorMessage());
        assertEquals("{}", summary.error().errorData());
        assertEquals(List.of("frame"), summary.error().stackTrace());
    }

    @Test
    void toSummaryUsesTheOperationStatus() {
        assertEquals(
                DistributedMapStatus.FAILED,
                DistributedMapOperation.toSummary(
                                DistributedMapStatus.FAILED, detailsBuilder().build())
                        .status());
    }

    @Test
    void toSummaryLeavesSourceExhaustedNullWhenAbsent() {
        var details = DistributedMapDetails.builder()
                .successCount(1L)
                .failureCount(0L)
                .readCount(1L)
                .build();
        assertNull(DistributedMapOperation.toSummary(DistributedMapStatus.FAILED, details)
                .sourceExhausted());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SuccessCount", "FailureCount", "ReadCount"})
    void toSummaryThrowsWhenATerminalCountIsMissing(String missing) {
        var details = DistributedMapDetails.builder()
                .successCount("SuccessCount".equals(missing) ? null : 1L)
                .failureCount("FailureCount".equals(missing) ? null : 0L)
                .readCount("ReadCount".equals(missing) ? null : 1L)
                .build();

        var exception = assertThrows(
                DurableExecutionException.class,
                () -> DistributedMapOperation.toSummary(DistributedMapStatus.FAILED, details));

        assertEquals("missing distributed map " + missing + " from backend", exception.getMessage());
    }

    @Test
    void toSummaryThrowsWhenASucceededRunHasNoTotalCount() {
        var details = DistributedMapDetails.builder()
                .sourceExhausted(true)
                .successCount(1L)
                .failureCount(0L)
                .readCount(1L)
                .build();

        var exception = assertThrows(
                DurableExecutionException.class,
                () -> DistributedMapOperation.toSummary(DistributedMapStatus.SUCCEEDED, details));

        assertEquals("missing distributed map TotalCount from backend", exception.getMessage());
    }

    @Test
    void toSummaryAcceptsAnAbsentTotalCountWhenTheRunDidNotSucceed() {
        // TotalCount is required only for a succeeded run, so a failed run may omit it.
        var details = DistributedMapDetails.builder()
                .successCount(0L)
                .failureCount(0L)
                .readCount(0L)
                .build();

        assertNull(DistributedMapOperation.toSummary(DistributedMapStatus.FAILED, details)
                .totalCount());
    }

    @Test
    void toSummaryLeavesTheRunErrorNullWhenAbsent() {
        assertNull(DistributedMapOperation.toSummary(
                        DistributedMapStatus.SUCCEEDED, detailsBuilder().build())
                .error());
    }

    // ------------------------------------------------------------------
    // toResult
    // ------------------------------------------------------------------

    @Test
    void toResultParsesSucceededAndFailedItems() {
        var details = detailsBuilder()
                .failureCount(1L)
                .results(List.of(
                        succeededItem("0", "42"),
                        failedItem(
                                "1",
                                ErrorObject.builder()
                                        .errorType("E")
                                        .errorMessage("boom")
                                        .build())))
                .build();
        var result = DistributedMapOperation.toResult(
                DistributedMapStatus.SUCCEEDED, details, TypeToken.get(Integer.class), SERDES);
        assertEquals(List.of(42), result.results());
        assertEquals(1, result.errors().size());
        assertEquals("E", result.errors().get(0).errorType());
        assertEquals("boom", result.errors().get(0).errorMessage());
    }

    @Test
    void toResultDecodesOutputExactlyOnceThroughSerdes() {
        var serdes = new UpperSerDes();
        var details =
                detailsBuilder().results(List.of(succeededItem("0", "abc"))).build();
        var result = DistributedMapOperation.toResult(
                DistributedMapStatus.SUCCEEDED, details, TypeToken.get(String.class), serdes);
        assertEquals(List.of("ABC"), result.results());
        assertEquals(1, serdes.deserializeCount);
    }

    @Test
    void toResultKeepsNullOutputNull() {
        var details =
                detailsBuilder().results(List.of(succeededItem("0", null))).build();
        var result = DistributedMapOperation.toResult(
                DistributedMapStatus.SUCCEEDED, details, TypeToken.get(String.class), SERDES);
        assertNull(result.succeeded().get(0).output());
        assertEquals(1, result.results().size());
        assertNull(result.results().get(0));
    }

    @Test
    void toResultKeepsNullItemErrorFieldsNull() {
        var details = detailsBuilder()
                .failureCount(1L)
                .results(List.of(failedItem("0", ErrorObject.builder().build())))
                .build();
        var error = DistributedMapOperation.toResult(
                        DistributedMapStatus.SUCCEEDED, details, TypeToken.get(String.class), SERDES)
                .errors()
                .get(0);
        assertNull(error.errorType());
        assertNull(error.errorMessage());
    }

    @Test
    void toResultCarriesEveryItemErrorMember() {
        var details = detailsBuilder()
                .failureCount(1L)
                .results(List.of(failedItem(
                        "0",
                        ErrorObject.builder()
                                .errorType("E")
                                .errorMessage("boom")
                                .errorData("{\"k\":1}")
                                .stackTrace(List.of("frame-1", "frame-2"))
                                .build())))
                .build();
        var error = DistributedMapOperation.toResult(
                        DistributedMapStatus.SUCCEEDED, details, TypeToken.get(String.class), SERDES)
                .errors()
                .get(0);
        assertEquals("E", error.errorType());
        assertEquals("boom", error.errorMessage());
        assertEquals("{\"k\":1}", error.errorData());
        assertEquals(List.of("frame-1", "frame-2"), error.stackTrace());
    }

    @Test
    void toResultKeepsFailedItemErrorNullWhenAbsent() {
        var details = detailsBuilder()
                .failureCount(1L)
                .results(List.of(failedItem("0", null)))
                .build();
        var result = DistributedMapOperation.toResult(
                DistributedMapStatus.SUCCEEDED, details, TypeToken.get(String.class), SERDES);
        assertNull(result.failed().get(0).error());
        assertEquals(1, result.errors().size());
        assertNull(result.errors().get(0));
    }

    @Test
    void toResultMapsUnrecognizedItemStatusToUnknownAndKeepsTheOtherItems() {
        var details = detailsBuilder()
                .failureCount(1L)
                .results(List.of(
                        succeededItem("0", "\"a\""),
                        DistributedMapResultItem.builder()
                                .itemId("1")
                                .status("BOGUS")
                                .build(),
                        failedItem("2", ErrorObject.builder().errorType("E").build())))
                .build();

        var result = DistributedMapOperation.toResult(
                DistributedMapStatus.SUCCEEDED, details, TypeToken.get(String.class), SERDES);

        assertEquals(3, result.items().size());
        assertEquals(
                DistributedMapResult.Item.Status.UNKNOWN_TO_SDK_VERSION,
                result.items().get(1).status());
        assertNull(result.items().get(1).output());
        assertNull(result.items().get(1).error());
        // The unknown item is in items() but in none of the four filtered accessors.
        assertEquals(
                List.of("0"),
                result.succeeded().stream()
                        .map(DistributedMapResult.Item::itemId)
                        .toList());
        assertEquals(
                List.of("2"),
                result.failed().stream().map(DistributedMapResult.Item::itemId).toList());
        assertEquals(List.of("a"), result.results());
        assertEquals(1, result.errors().size());
    }

    @Test
    void toResultThrowsOnMissingItemStatus() {
        var details = detailsBuilder()
                .results(List.of(DistributedMapResultItem.builder().itemId("0").build()))
                .build();
        assertThrows(
                DurableExecutionException.class,
                () -> DistributedMapOperation.toResult(
                        DistributedMapStatus.SUCCEEDED, details, TypeToken.get(String.class), SERDES));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void toResultThrowsOnMissingItemId(String itemId) {
        var details = detailsBuilder()
                .results(List.of(DistributedMapResultItem.builder()
                        .itemId(itemId)
                        .status("SUCCEEDED")
                        .output("\"x\"")
                        .build()))
                .build();
        var e = assertThrows(
                DurableExecutionException.class,
                () -> DistributedMapOperation.toResult(
                        DistributedMapStatus.SUCCEEDED, details, TypeToken.get(String.class), SERDES));
        assertEquals("missing distributed map item id from backend", e.getMessage());
    }
}
