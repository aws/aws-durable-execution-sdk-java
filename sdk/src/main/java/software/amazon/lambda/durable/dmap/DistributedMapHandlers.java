// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DistributedMapError;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/** Authoring helpers that wrap a customer function into a distributed map processor or reader Lambda handler. */
public final class DistributedMapHandlers {
    private static final SerDes JSON = new JacksonSerDes();
    private static final TypeToken<String> OUTPUT_TYPE = TypeToken.get(String.class);
    private static final int MIN_CONCURRENCY = 1;
    private static final int DEFAULT_CONCURRENCY = 1;
    private static final TypeToken<ProcessorEvent> EVENT_TYPE = TypeToken.get(ProcessorEvent.class);
    private static final int READER_STATE_LIMIT = 32 * 1024;
    private static final String ITEMS_OP_NAME = "distributed-map-items";

    private DistributedMapHandlers() {}

    /** Reader function: returns the page that follows the given state. */
    @FunctionalInterface
    public interface ReaderFunction<I, S> {

        /**
         * Reads one page.
         *
         * @param state the state from the previous page, or null on the first call
         * @param maxItemsPerPage the most items the page may hold
         * @param context the reader context for this page request
         * @return the page of items and the next state, or a null next state when the source is exhausted
         */
        ReaderPage<I, S> apply(S state, Integer maxItemsPerPage, ReaderContext context);
    }

    /** Durable item function: processes one item under its own DurableContext. */
    @FunctionalInterface
    public interface DurableItemFunction<I, O> {

        /**
         * Processes one item.
         *
         * @param context the durable context for this item's execution
         * @param item the item to process
         * @param itemContext the item context for this item
         * @return the item's output
         */
        O apply(DurableContext context, I item, ItemContext itemContext);
    }

    /** Durable batch function: processes a whole batch under the batch's DurableContext. */
    @FunctionalInterface
    public interface DurableBatchFunction<I> {

        /**
         * Processes the batch.
         *
         * @param context the durable context for the batch's execution
         * @param items the items in the batch
         * @param batchContext the batch context for the batch
         */
        void accept(DurableContext context, List<I> items, BatchContext batchContext);
    }

    private static void validateConcurrency(int concurrency) {
        if (concurrency < MIN_CONCURRENCY) {
            throw new IllegalArgumentException(
                    "concurrency must be at least " + MIN_CONCURRENCY + ", got: " + concurrency);
        }
    }

    /** Wraps a per-item function as a processor handler, running one item at a time. */
    public static <I, O> RequestHandler<ProcessorEvent, ItemHandlerResponse> createDistributedMapItemHandler(
            BiFunction<I, ItemContext, O> func, TypeToken<I> itemType, SerDes itemSerDes, SerDes resultSerDes) {
        return createDistributedMapItemHandler(func, itemType, itemSerDes, resultSerDes, DEFAULT_CONCURRENCY);
    }

    /**
     * Wraps a per-item function as a processor handler. Pair it with DistributedMapProcessor.reportItemOutcomes.
     *
     * @param concurrency the maximum number of items processed at the same time, at least 1. When greater than 1, the
     *     function, itemSerDes and resultSerDes are called from several threads at the same time, so each must be safe
     *     for concurrent calls.
     */
    public static <I, O> RequestHandler<ProcessorEvent, ItemHandlerResponse> createDistributedMapItemHandler(
            BiFunction<I, ItemContext, O> func,
            TypeToken<I> itemType,
            SerDes itemSerDes,
            SerDes resultSerDes,
            int concurrency) {
        validateConcurrency(concurrency);
        var inSerdes = itemSerDes != null ? itemSerDes : JSON;
        var outSerdes = resultSerDes != null ? resultSerDes : JSON;
        return (event, context) -> {
            var records = event.records();
            var outputs = new String[records.size()];
            var errors = new Throwable[records.size()];
            var pool = Executors.newFixedThreadPool(concurrency);
            try {
                var futures = new ArrayList<Future<?>>();
                for (var i = 0; i < records.size(); i++) {
                    var index = i;
                    futures.add(pool.submit(() -> {
                        var record = records.get(index);
                        I item = toItem(inSerdes, record.body(), itemType);
                        O output = func.apply(item, new ItemContext(event.distributedMapRunArn(), record.itemId()));
                        outputs[index] = toOutput(outSerdes, output);
                        return null;
                    }));
                }
                for (var i = 0; i < futures.size(); i++) {
                    try {
                        futures.get(i).get();
                    } catch (ExecutionException e) {
                        errors[i] = e.getCause() != null ? e.getCause() : e;
                    }
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Distributed map item handler interrupted", e);
            } finally {
                pool.shutdown();
            }

            var results = new ArrayList<ItemResult>();
            var failures = new ArrayList<ItemFailure>();
            for (var i = 0; i < records.size(); i++) {
                var itemId = records.get(i).itemId();
                if (errors[i] != null) {
                    failures.add(new ItemFailure(itemId, DistributedMapError.of(errors[i])));
                } else {
                    results.add(new ItemResult(itemId, outputs[i]));
                }
            }
            return new ItemHandlerResponse(results, failures);
        };
    }

    /**
     * Wraps a whole-batch function as a processor handler. Returning succeeds every item, throwing fails the batch.
     * Pair it with DistributedMapProcessor.reportBatchOutcome.
     */
    public static <I> RequestHandler<ProcessorEvent, Void> createDistributedMapBatchHandler(
            BiConsumer<List<I>, BatchContext> func, TypeToken<I> itemType, SerDes itemSerDes) {
        var serdes = itemSerDes != null ? itemSerDes : JSON;
        return (event, context) -> {
            func.accept(
                    toItems(serdes, event, itemType),
                    new BatchContext(
                            event.distributedMapRunArn(),
                            event.records().stream()
                                    .map(ProcessorRecord::itemId)
                                    .toList()));
            return null;
        };
    }

    /**
     * Wraps a reader function as a source handler. A null nextState signals the source is exhausted.
     *
     * <p>itemSerDes must be the serdes the processor is configured with, since the processor deserializes every item
     * body with its own item serdes. The reader and the processor are separate Lambdas, so nothing checks that they
     * match.
     */
    public static <I, S> RequestHandler<ReaderEvent, ReaderResponse> createDistributedMapReader(
            ReaderFunction<I, S> func, TypeToken<S> stateType, SerDes stateSerDes, SerDes itemSerDes) {
        var serdes = stateSerDes != null ? stateSerDes : JSON;
        var itemSerdes = itemSerDes != null ? itemSerDes : JSON;
        return (event, context) -> {
            S state = event.state() != null ? serdes.deserialize(event.state(), stateType) : null;

            var page = func.apply(state, event.maxItemsPerPage(), new ReaderContext(event.distributedMapRunArn()));
            if (page.items().size() > event.maxItemsPerPage()) {
                throw new IllegalStateException("reader returned "
                        + page.items().size() + " items, exceeding maxItemsPerPage " + event.maxItemsPerPage());
            }
            var items = new ArrayList<String>(page.items().size());
            var index = 0;
            for (I item : page.items()) {
                var body = itemSerdes.serialize(item);
                if (body == null) {
                    // The service requires every item to be a string, so a null item is sent as the JSON literal null.
                    if (item != null) {
                        throw new IllegalStateException(
                                "serialization of reader item " + index + " produced no string");
                    }
                    body = "null";
                }
                items.add(body);
                index++;
            }
            String nextState = null;
            if (page.nextState() != null) {
                nextState = serdes.serialize(page.nextState());
                if (nextState == null) {
                    throw new IllegalStateException("serialization of reader nextState produced no string");
                }
                if (nextState.getBytes(StandardCharsets.UTF_8).length > READER_STATE_LIMIT) {
                    throw new IllegalStateException(
                            "reader nextState exceeds the " + (READER_STATE_LIMIT / 1024) + " KB limit");
                }
                // The next call would repeat the same position, and the service rejects it as a source failure.
                if (nextState.equals(event.state())) {
                    throw new IllegalStateException(
                            "reader nextState must differ from the state it was called with, or the next page repeats the same position");
                }
            }
            return new ReaderResponse(items, nextState);
        };
    }

    /** Durable variant of the item handler, running one item at a time. */
    public static <I, O>
            RequestHandler<DurableExecutionInput, DurableExecutionOutput>
                    createDistributedMapItemHandlerWithDurableExecution(
                            DurableItemFunction<I, O> func,
                            TypeToken<I> itemType,
                            SerDes itemSerDes,
                            SerDes resultSerDes) {
        return createDistributedMapItemHandlerWithDurableExecution(
                func, itemType, itemSerDes, resultSerDes, DEFAULT_CONCURRENCY, null);
    }

    /** Durable variant of the item handler, running one item at a time, with a durable execution config. */
    public static <I, O>
            RequestHandler<DurableExecutionInput, DurableExecutionOutput>
                    createDistributedMapItemHandlerWithDurableExecution(
                            DurableItemFunction<I, O> func,
                            TypeToken<I> itemType,
                            SerDes itemSerDes,
                            SerDes resultSerDes,
                            DurableConfig durableConfig) {
        return createDistributedMapItemHandlerWithDurableExecution(
                func, itemType, itemSerDes, resultSerDes, DEFAULT_CONCURRENCY, durableConfig);
    }

    /**
     * Durable variant of the item handler. The function receives the per-item DurableContext, the item and its
     * ItemContext.
     *
     * @param concurrency the maximum number of items processed at the same time, at least 1. When greater than 1, the
     *     function, itemSerDes and resultSerDes are called from several threads at the same time, so each must be safe
     *     for concurrent calls.
     */
    public static <I, O>
            RequestHandler<DurableExecutionInput, DurableExecutionOutput>
                    createDistributedMapItemHandlerWithDurableExecution(
                            DurableItemFunction<I, O> func,
                            TypeToken<I> itemType,
                            SerDes itemSerDes,
                            SerDes resultSerDes,
                            int concurrency) {
        return createDistributedMapItemHandlerWithDurableExecution(
                func, itemType, itemSerDes, resultSerDes, concurrency, null);
    }

    /**
     * Durable variant of the item handler. The function receives the per-item DurableContext, the item and its
     * ItemContext.
     *
     * @param concurrency the maximum number of items processed at the same time, at least 1. When greater than 1, the
     *     function, itemSerDes and resultSerDes are called from several threads at the same time, so each must be safe
     *     for concurrent calls.
     * @param durableConfig the config the wrapped durable execution runs under, for example to register plugins or
     *     supply a client. A null uses DurableConfig.defaultConfig.
     */
    public static <I, O>
            RequestHandler<DurableExecutionInput, DurableExecutionOutput>
                    createDistributedMapItemHandlerWithDurableExecution(
                            DurableItemFunction<I, O> func,
                            TypeToken<I> itemType,
                            SerDes itemSerDes,
                            SerDes resultSerDes,
                            int concurrency,
                            DurableConfig durableConfig) {
        validateConcurrency(concurrency);
        var inSerdes = itemSerDes != null ? itemSerDes : JSON;
        var outSerdes = resultSerDes != null ? resultSerDes : JSON;
        return DurableExecutor.wrap(
                EVENT_TYPE,
                (event, ctx) -> {
                    var records = event.records();
                    var bodies = new ArrayList<String>(records.size());
                    for (var record : records) {
                        bodies.add(record.body());
                    }
                    MapResult<String> batch = ctx.map(
                            ITEMS_OP_NAME,
                            bodies,
                            OUTPUT_TYPE,
                            (body, index, mapContext) -> {
                                var itemContext = new ItemContext(
                                        event.distributedMapRunArn(),
                                        records.get(index).itemId());
                                O output = func.apply(mapContext, toItem(inSerdes, body, itemType), itemContext);
                                return toOutput(outSerdes, output);
                            },
                            MapConfig.builder()
                                    .completionConfig(CompletionConfig.allCompleted())
                                    .maxConcurrency(concurrency)
                                    .build());

                    var results = new ArrayList<ItemResult>();
                    var failures = new ArrayList<ItemFailure>();
                    for (var i = 0; i < records.size(); i++) {
                        var itemId = records.get(i).itemId();
                        var item = batch.getItem(i);
                        if (item.status() == MapResult.MapResultItem.Status.SUCCEEDED) {
                            results.add(new ItemResult(itemId, item.result()));
                        } else {
                            var error = item.error();
                            failures.add(new ItemFailure(
                                    itemId,
                                    error != null
                                            ? new DistributedMapError(
                                                    error.errorType(), error.errorMessage(), null, error.stackTrace())
                                            : null));
                        }
                    }
                    return new ItemHandlerResponse(results, failures);
                },
                configOrDefault(durableConfig));
    }

    /**
     * Durable variant of the batch handler. The function receives the DurableContext, the items and their BatchContext.
     */
    public static <I>
            RequestHandler<DurableExecutionInput, DurableExecutionOutput>
                    createDistributedMapBatchHandlerWithDurableExecution(
                            DurableBatchFunction<I> func, TypeToken<I> itemType, SerDes itemSerDes) {
        return createDistributedMapBatchHandlerWithDurableExecution(func, itemType, itemSerDes, null);
    }

    /**
     * Durable variant of the batch handler. The function receives the DurableContext, the items and their BatchContext.
     *
     * @param durableConfig the config the wrapped durable execution runs under, for example to register plugins or
     *     supply a client. A null uses DurableConfig.defaultConfig.
     */
    public static <I>
            RequestHandler<DurableExecutionInput, DurableExecutionOutput>
                    createDistributedMapBatchHandlerWithDurableExecution(
                            DurableBatchFunction<I> func,
                            TypeToken<I> itemType,
                            SerDes itemSerDes,
                            DurableConfig durableConfig) {
        var serdes = itemSerDes != null ? itemSerDes : JSON;
        return DurableExecutor.wrap(
                EVENT_TYPE,
                (event, ctx) -> {
                    func.accept(
                            ctx,
                            toItems(serdes, event, itemType),
                            new BatchContext(
                                    event.distributedMapRunArn(),
                                    event.records().stream()
                                            .map(ProcessorRecord::itemId)
                                            .toList()));
                    return null;
                },
                configOrDefault(durableConfig));
    }

    private static DurableConfig configOrDefault(DurableConfig durableConfig) {
        return durableConfig != null ? durableConfig : DurableConfig.defaultConfig();
    }

    private static <I> List<I> toItems(SerDes serdes, ProcessorEvent event, TypeToken<I> itemType) {
        var records = event.records();
        var items = new ArrayList<I>(records.size());
        for (var record : records) {
            items.add(toItem(serdes, record.body(), itemType));
        }
        return items;
    }

    private static <I> I toItem(SerDes serdes, String body, TypeToken<I> itemType) {
        return serdes.deserialize(body, itemType);
    }

    /** Serializes an item's output, or returns null when the handler returned nothing. */
    private static String toOutput(SerDes serdes, Object output) {
        if (output == null) {
            return null;
        }
        var body = serdes.serialize(output);
        if (body == null) {
            throw new IllegalStateException("serialization of item output produced no string");
        }
        return body;
    }
}
