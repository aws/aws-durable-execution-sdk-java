// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import software.amazon.awssdk.services.lambda.model.DistributedMapCsvDelimiter;
import software.amazon.awssdk.services.lambda.model.DistributedMapCsvFormatOptions;
import software.amazon.awssdk.services.lambda.model.DistributedMapCsvHeaderLocation;
import software.amazon.awssdk.services.lambda.model.DistributedMapDestinationType;
import software.amazon.awssdk.services.lambda.model.DistributedMapDetails;
import software.amazon.awssdk.services.lambda.model.DistributedMapFunctionResponseType;
import software.amazon.awssdk.services.lambda.model.DistributedMapInlineSourceConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapOnFailureConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapOnFailureInclude;
import software.amazon.awssdk.services.lambda.model.DistributedMapOnSuccessConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapOnSuccessInclude;
import software.amazon.awssdk.services.lambda.model.DistributedMapOptions;
import software.amazon.awssdk.services.lambda.model.DistributedMapProcessorConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapReaderFunctionSourceConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapResultCollectionConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapResultCollectionMode;
import software.amazon.awssdk.services.lambda.model.DistributedMapS3DestinationConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapS3ListedObjectMetadataConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapS3ListedObjectsConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapS3ObjectConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapS3SourceConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapS3SourceType;
import software.amazon.awssdk.services.lambda.model.DistributedMapSourceConfig;
import software.amazon.awssdk.services.lambda.model.DistributedMapSourceFormat;
import software.amazon.awssdk.services.lambda.model.DistributedMapSourceType;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.DistributedMapConfig;
import software.amazon.lambda.durable.config.DistributedMapDestination;
import software.amazon.lambda.durable.config.DistributedMapProcessor;
import software.amazon.lambda.durable.config.DistributedMapSource;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableExecutionException;
import software.amazon.lambda.durable.model.DistributedMapError;
import software.amazon.lambda.durable.model.DistributedMapResult;
import software.amazon.lambda.durable.model.DistributedMapStatus;
import software.amazon.lambda.durable.model.DistributedMapSummary;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Durable operation that starts a distributed map run and waits for its terminal outcome.
 *
 * @param <R> the resolved result type (a summary, or a result with collected items)
 */
public class DistributedMapOperation<R> extends SerializableDurableOperation<R> {
    private final DistributedMapOptions options;
    private final BiFunction<DistributedMapStatus, DistributedMapDetails, R> resultBuilder;

    private static final SerDes JSON = new JacksonSerDes();
    private static final long INLINE_SIZE_LIMIT = 1024L * 1024L;
    private static final long READER_STATE_LIMIT = 32L * 1024L;

    public DistributedMapOperation(
            OperationIdentifier operationIdentifier,
            DistributedMapOptions options,
            TypeToken<R> resultType,
            SerDes serDes,
            BiFunction<DistributedMapStatus, DistributedMapDetails, R> resultBuilder,
            DurableContextImpl durableContext) {
        super(operationIdentifier, resultType, serDes, durableContext);
        this.options = options;
        this.resultBuilder = resultBuilder;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        sendOperationUpdate(
                OperationUpdate.builder().action(OperationAction.START).distributedMapOptions(options));
        pollUntilTerminal();
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            // The start checkpoint records the operation as PENDING, and it reaches STARTED only once the
            // service confirms the run was created. A replay in that window waits for the outcome.
            case PENDING, STARTED -> pollUntilTerminal();
            case SUCCEEDED, FAILED, TIMED_OUT, STOPPED -> markAlreadyCompleted();
            default ->
                throw terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected distributed map status: " + existing.statusAsString());
        }
    }

    /**
     * Polls until the operation reaches a terminal status. A poll completes on any update, so a PENDING to STARTED
     * update would otherwise end polling while get() is still waiting for the outcome.
     */
    private void pollUntilTerminal() {
        pollForOperationUpdates().thenAccept(op -> {
            if (op != null && (op.status() == OperationStatus.PENDING || op.status() == OperationStatus.STARTED)) {
                pollUntilTerminal();
            }
        });
    }

    /** Blocks until the operation completes and returns the resolved outcome. */
    @Override
    public R get() {
        var op = waitForOperationCompletion();
        // An unsuccessful run resolves with its summary, so callers can read the counts. Use
        // DistributedMapSummary.throwIfError to opt into raising instead.
        var runStatus = switch (op.status()) {
            case SUCCEEDED -> DistributedMapStatus.SUCCEEDED;
            case FAILED -> DistributedMapStatus.FAILED;
            case TIMED_OUT -> DistributedMapStatus.TIMED_OUT;
            case STOPPED -> DistributedMapStatus.STOPPED;
            default ->
                throw terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected distributed map operation status: " + op.statusAsString());
        };
        var details = op.distributedMapDetails();
        if (details == null) {
            throw terminateExecutionWithIllegalDurableOperationException("DISTRIBUTED_MAP operation reached "
                    + op.statusAsString() + " but carried no DistributedMapDetails");
        }
        // SourceExhausted is required at terminal once a run exists, and is absent only when the operation
        // failed before one was created, which is also when the run ARN is absent.
        if (details.distributedMapRunArn() != null && details.sourceExhausted() == null) {
            throw terminateExecutionWithIllegalDurableOperationException(
                    "DISTRIBUTED_MAP operation reached " + op.statusAsString() + " but carried no SourceExhausted");
        }
        return resultBuilder.apply(runStatus, details);
    }

    public static DistributedMapOptions toOptions(
            DistributedMapSource<?> source,
            DistributedMapProcessor processor,
            int maxConcurrency,
            DistributedMapConfig config,
            SerDes defaultSerDes,
            boolean collectResults) {
        var builder = DistributedMapOptions.builder()
                .maxConcurrency(maxConcurrency)
                .source(sourceConfig(source, defaultSerDes))
                .processor(processorConfig(processor));
        if (config.destination() != null) {
            var destination = destinationConfig(config.destination());
            if (destination != null) {
                builder.destination(destination);
            }
        }
        var completion = completionConfig(config);
        if (completion != null) {
            builder.completionConfig(completion);
        }
        if (collectResults) {
            builder.resultCollection(DistributedMapResultCollectionConfig.builder()
                    .mode(DistributedMapResultCollectionMode.INLINE)
                    .build());
        }
        if (config.timeout() != null) {
            builder.timeoutSeconds((int) config.timeout().toSeconds());
        }
        return builder.build();
    }

    private static DistributedMapSourceConfig sourceConfig(DistributedMapSource<?> source, SerDes defaultSerDes) {
        var builder = DistributedMapSourceConfig.builder()
                .type(DistributedMapSourceType.fromValue(source.sourceType().name()));
        if (source.maxItems() != null) {
            builder.maxItemsToRead(source.maxItems().longValue());
        }
        switch (source.sourceType()) {
            case INLINE -> builder.inlineSourceConfig(inlineSourceConfig(source, defaultSerDes));
            case S3 -> builder.s3SourceConfig(s3SourceConfig(source.s3()));
            case READER_FUNCTION -> builder.readerFunctionSourceConfig(readerConfig(source.reader(), defaultSerDes));
        }
        return builder.build();
    }

    private static DistributedMapInlineSourceConfig inlineSourceConfig(
            DistributedMapSource<?> source, SerDes defaultSerDes) {
        var serdes = source.inlineSerDes() != null ? source.inlineSerDes() : defaultSerDes;
        var bodies = new ArrayList<String>(source.inlineItems().size());
        var items = source.inlineItems();
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var body = serdes.serialize(item);
            if (body == null) {
                // The service requires every item to be a string. The default serdes returns null for a null item,
                // so a null item is sent as the JSON literal null. Any other item with no serialized form is an error.
                if (item != null) {
                    throw new IllegalArgumentException("serialization of inline item " + index + " produced no string");
                }
                body = "null";
            }
            bodies.add(body);
        }
        var arrayBytes = JSON.serialize(bodies).getBytes(StandardCharsets.UTF_8).length;
        if (arrayBytes > INLINE_SIZE_LIMIT) {
            throw new IllegalArgumentException(
                    "inline source exceeds the 1 MB limit (serialized size: " + arrayBytes + " bytes)");
        }
        return DistributedMapInlineSourceConfig.builder().items(bodies).build();
    }

    private static DistributedMapS3SourceConfig s3SourceConfig(DistributedMapSource.S3SourceConfig s3) {
        var builder = DistributedMapS3SourceConfig.builder()
                .type(DistributedMapS3SourceType.fromValue(s3.type().name()))
                .bucket(s3.bucket());
        if (s3.expectedBucketOwner() != null) {
            builder.expectedBucketOwner(s3.expectedBucketOwner());
        }
        if (s3.objectConfig() != null) {
            var object = s3.objectConfig();
            var block = DistributedMapS3ObjectConfig.builder()
                    .key(object.key())
                    .format(DistributedMapSourceFormat.fromValue(object.format().name()));
            if (object.csvFormat() != null) {
                block.csvFormatOptions(csvFormatOptions(object.csvFormat()));
            }
            builder.objectConfig(block.build());
        }
        if (s3.listedObjectMetadataConfig() != null) {
            builder.listedObjectMetadataConfig(DistributedMapS3ListedObjectMetadataConfig.builder()
                    .keyPrefix(s3.listedObjectMetadataConfig().keyPrefix())
                    .build());
        }
        if (s3.listedObjectsConfig() != null) {
            var listed = s3.listedObjectsConfig();
            var block = DistributedMapS3ListedObjectsConfig.builder()
                    .keyPrefix(listed.keyPrefix())
                    .format(DistributedMapSourceFormat.fromValue(listed.format().name()));
            if (listed.csvFormat() != null) {
                block.csvFormatOptions(csvFormatOptions(listed.csvFormat()));
            }
            builder.listedObjectsConfig(block.build());
        }
        return builder.build();
    }

    private static DistributedMapCsvFormatOptions csvFormatOptions(DistributedMapSource.CsvFormat format) {
        var builder = DistributedMapCsvFormatOptions.builder()
                .headerLocation(DistributedMapCsvHeaderLocation.fromValue(
                        format.headerLocation().name()))
                .delimiter(
                        DistributedMapCsvDelimiter.fromValue(format.delimiter().name()));
        // Column names go on the wire only for headerless files (GIVEN), expectedColumns (FIRST_ROW) is compile-time
        // only.
        if (format.headerLocation() == DistributedMapSource.CsvFormat.HeaderLocation.GIVEN
                && format.columns() != null) {
            builder.headers(format.columns());
        }
        return builder.build();
    }

    private static DistributedMapReaderFunctionSourceConfig readerConfig(
            DistributedMapSource.ReaderSourceConfig reader, SerDes defaultSerDes) {
        var builder = DistributedMapReaderFunctionSourceConfig.builder().functionName(reader.functionName());
        if (reader.initialState() != null) {
            var serdes = reader.stateSerDes() != null ? reader.stateSerDes() : defaultSerDes;
            var state = serdes.serialize(reader.initialState());
            if (state == null) {
                throw new IllegalArgumentException("serialization of reader initialState produced no string");
            }
            if (state.getBytes(StandardCharsets.UTF_8).length > READER_STATE_LIMIT) {
                throw new IllegalArgumentException("reader initialState exceeds the 32 KB limit");
            }
            builder.initialState(state);
        }
        return builder.build();
    }

    private static DistributedMapProcessorConfig processorConfig(DistributedMapProcessor processor) {
        var builder = DistributedMapProcessorConfig.builder().functionName(processor.functionName());
        if (processor.durableExecutionNamePrefix() != null) {
            builder.durableExecutionNamePrefix(processor.durableExecutionNamePrefix());
        }
        if (processor.responseMode() == DistributedMapProcessor.ResponseMode.ITEM_OUTCOMES) {
            builder.functionResponseTypes(List.of(DistributedMapFunctionResponseType.REPORT_BATCH_ITEM_OUTCOMES));
        }
        if (processor.batchSize() != null) {
            builder.batchSize(processor.batchSize());
        }
        if (processor.retry() != null) {
            var retry = processor.retry();
            if (retry.maxRetryAttempts() != null) {
                builder.maxRetryAttempts(retry.maxRetryAttempts());
            }
            if (retry.maxRetryDuration() != null) {
                builder.maxRetryDurationSeconds((int) retry.maxRetryDuration().toSeconds());
            }
        }
        return builder.build();
    }

    private static software.amazon.awssdk.services.lambda.model.DistributedMapDestinationConfig destinationConfig(
            software.amazon.lambda.durable.config.DistributedMapDestination destination) {
        if (destination.onSuccess() == null && destination.onFailure() == null) {
            return null; // omit an empty destination config from the wire
        }
        var builder = software.amazon.awssdk.services.lambda.model.DistributedMapDestinationConfig.builder();
        DistributedMapDestination.Success onSuccess = destination.onSuccess();
        if (onSuccess != null) {
            var include = new ArrayList<DistributedMapOnSuccessInclude>();
            if (onSuccess.includeInput()) {
                include.add(DistributedMapOnSuccessInclude.INPUT);
            }
            if (onSuccess.includeOutput()) {
                include.add(DistributedMapOnSuccessInclude.OUTPUT);
            }
            builder.onSuccess(DistributedMapOnSuccessConfig.builder()
                    .type(DistributedMapDestinationType.S3)
                    .include(include)
                    .s3DestinationConfig(
                            s3Destination(onSuccess.bucket(), onSuccess.prefix(), onSuccess.expectedBucketOwner()))
                    .build());
        }
        DistributedMapDestination.Failure onFailure = destination.onFailure();
        if (onFailure != null) {
            var include = new ArrayList<DistributedMapOnFailureInclude>();
            if (onFailure.includeInput()) {
                include.add(DistributedMapOnFailureInclude.INPUT);
            }
            if (onFailure.includeError()) {
                include.add(DistributedMapOnFailureInclude.ERROR);
            }
            builder.onFailure(DistributedMapOnFailureConfig.builder()
                    .type(DistributedMapDestinationType.S3)
                    .include(include)
                    .s3DestinationConfig(
                            s3Destination(onFailure.bucket(), onFailure.prefix(), onFailure.expectedBucketOwner()))
                    .build());
        }
        return builder.build();
    }

    private static DistributedMapS3DestinationConfig s3Destination(
            String bucket, String keyPrefix, String expectedBucketOwner) {
        var builder = DistributedMapS3DestinationConfig.builder().bucket(bucket).keyPrefix(keyPrefix);
        if (expectedBucketOwner != null) {
            builder.expectedBucketOwner(expectedBucketOwner);
        }
        return builder.build();
    }

    private static software.amazon.awssdk.services.lambda.model.DistributedMapCompletionConfig completionConfig(
            DistributedMapConfig config) {
        var completion = config.completionConfig();
        if (completion == null) {
            return null;
        }
        var builder = software.amazon.awssdk.services.lambda.model.DistributedMapCompletionConfig.builder();
        if (completion.toleratedFailureCount() != null) {
            builder.toleratedFailureCount(completion.toleratedFailureCount());
        }
        if (completion.toleratedFailurePercentage() != null) {
            builder.toleratedFailurePercentage(
                    completion.toleratedFailurePercentage().floatValue());
        }
        if (completion.minimumSampleSize() != null) {
            builder.minimumSampleSize(completion.minimumSampleSize());
        }
        return builder.build();
    }

    public static DistributedMapSummary toSummary(DistributedMapStatus runStatus, DistributedMapDetails details) {
        // The service sends all three counts at every terminal state, and TotalCount once the run succeeded.
        // Reading an absent count as zero would report a run that failed items as having none, so a missing
        // count is a contract violation rather than a default.
        var successCount = requireCount(details.successCount(), "SuccessCount");
        var failureCount = requireCount(details.failureCount(), "FailureCount");
        var readCount = requireCount(details.readCount(), "ReadCount");
        if (runStatus == DistributedMapStatus.SUCCEEDED && details.totalCount() == null) {
            throw new DurableExecutionException("missing distributed map TotalCount from backend");
        }
        return new DistributedMapSummary(
                runStatus,
                details.sourceExhausted(),
                successCount,
                failureCount,
                readCount,
                details.distributedMapRunArn(),
                toError(details.error()),
                details.totalCount());
    }

    private static long requireCount(Long count, String field) {
        if (count == null) {
            throw new DurableExecutionException("missing distributed map " + field + " from backend");
        }
        return count;
    }

    public static <O> DistributedMapResult<O> toResult(
            DistributedMapStatus runStatus, DistributedMapDetails details, TypeToken<O> resultType, SerDes serdes) {
        var summary = toSummary(runStatus, details);
        var items = new ArrayList<DistributedMapResult.Item<O>>();
        if (details.results() != null) {
            for (var wire : details.results()) {
                if (wire.itemId() == null || wire.itemId().isEmpty()) {
                    throw new DurableExecutionException("missing distributed map item id from backend");
                }
                var status = wire.statusAsString();
                if (status == null) {
                    throw new DurableExecutionException("distributed map item " + wire.itemId()
                            + " has no status, which the service model" + " requires");
                }
                if (DistributedMapResult.Item.Status.SUCCEEDED.name().equals(status)) {
                    O output = wire.output() != null ? serdes.deserialize(wire.output(), resultType) : null;
                    items.add(DistributedMapResult.Item.succeeded(wire.itemId(), output));
                } else if (DistributedMapResult.Item.Status.FAILED.name().equals(status)) {
                    items.add(DistributedMapResult.Item.failed(wire.itemId(), toError(wire.error())));
                } else {
                    // A status added after this SDK version. Keep the item so the other items survive.
                    items.add(DistributedMapResult.Item.unknown(wire.itemId()));
                }
            }
        }
        return new DistributedMapResult<>(summary, items);
    }

    private static DistributedMapError toError(ErrorObject error) {
        return error == null
                ? null
                : new DistributedMapError(
                        error.errorType(), error.errorMessage(), error.errorData(), error.stackTrace());
    }
}
