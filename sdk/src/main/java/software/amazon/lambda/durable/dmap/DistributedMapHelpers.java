// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.model.DistributedMapError;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Helpers a distributed map processor or reader Lambda calls inside its own handler.
 *
 * <p>Each helper returns the response the distributed map service expects. The handler must return that response.
 */
public final class DistributedMapHelpers {
    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedMapHelpers.class);
    private static final SerDes JSON = new JacksonSerDes();
    private static final TypeToken<String> OUTPUT_TYPE = TypeToken.get(String.class);
    private static final int MIN_CONCURRENCY = 1;
    private static final int DEFAULT_CONCURRENCY = 1;
    private static final String ITEMS_OP_NAME = "distributed-map-items";

    private DistributedMapHelpers() {}

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

    /**
     * Calls {@code func} on each item in the batch, and returns each item's result or error in the format the service
     * expects, running one item at a time.
     *
     * <p>Call it in the handler of your non-durable processor Lambda, and pass that Lambda's name to
     * {@code DistributedMapProcessor.reportItemOutcomes} in the parent's distributed map call.
     *
     * <p>Note: your handler must return this method's result, because the service reads which items failed from the
     * handler's return value. If the handler returns nothing, the service marks every item as succeeded.
     *
     * <p>It logs one ERROR line per failed item, with the run ARN, the item id and the reported error.
     *
     * <pre>{@code
     * public class OrderProcessor implements RequestHandler<ProcessorEvent, ItemHandlerResponse> {
     *     public ItemHandlerResponse handleRequest(ProcessorEvent event, Context context) {
     *         return DistributedMapHelpers.processDistributedMapItems(
     *                 event, (Order order, ItemContext itemContext) -> chargeOrder(order),
     *                 TypeToken.get(Order.class), null, null);
     *     }
     * }
     * }</pre>
     *
     * @param event the processor event the service sent
     * @param func the function to call on each item
     * @param itemType the item type
     * @param itemSerDes the serdes that reads each item, or null for the default JSON serdes
     * @param resultSerDes the serdes that writes each output, or null for the default JSON serdes
     * @return the response the handler must return
     */
    public static <I, O> ItemHandlerResponse processDistributedMapItems(
            ProcessorEvent event,
            BiFunction<I, ItemContext, O> func,
            TypeToken<I> itemType,
            SerDes itemSerDes,
            SerDes resultSerDes) {
        return processDistributedMapItems(event, func, itemType, itemSerDes, resultSerDes, DEFAULT_CONCURRENCY);
    }

    /**
     * Calls {@code func} on each item in the batch, and returns each item's result or error in the format the service
     * expects.
     *
     * <p>Call it in the handler of your non-durable processor Lambda, and pass that Lambda's name to
     * {@code DistributedMapProcessor.reportItemOutcomes} in the parent's distributed map call.
     *
     * <p>Note: your handler must return this method's result, because the service reads which items failed from the
     * handler's return value. If the handler returns nothing, the service marks every item as succeeded.
     *
     * <p>It logs one ERROR line per failed item, with the run ARN, the item id and the reported error.
     *
     * <pre>{@code
     * public class OrderProcessor implements RequestHandler<ProcessorEvent, ItemHandlerResponse> {
     *     public ItemHandlerResponse handleRequest(ProcessorEvent event, Context context) {
     *         return DistributedMapHelpers.processDistributedMapItems(
     *                 event, (Order order, ItemContext itemContext) -> chargeOrder(order),
     *                 TypeToken.get(Order.class), null, null, 5);
     *     }
     * }
     * }</pre>
     *
     * @param event the processor event the service sent
     * @param func the function to call on each item
     * @param itemType the item type
     * @param itemSerDes the serdes that reads each item, or null for the default JSON serdes
     * @param resultSerDes the serdes that writes each output, or null for the default JSON serdes
     * @param concurrency the maximum number of items processed at the same time (default: 1), at least 1. When greater
     *     than 1, {@code func}, {@code itemSerDes} and {@code resultSerDes} are called from several threads at the same
     *     time, so each must be safe for concurrent calls.
     * @return the response the handler must return
     */
    public static <I, O> ItemHandlerResponse processDistributedMapItems(
            ProcessorEvent event,
            BiFunction<I, ItemContext, O> func,
            TypeToken<I> itemType,
            SerDes itemSerDes,
            SerDes resultSerDes,
            int concurrency) {
        validateConcurrency(concurrency);
        var inSerdes = itemSerDes != null ? itemSerDes : JSON;
        var outSerdes = resultSerDes != null ? resultSerDes : JSON;
        var records = event.records();
        var outputs = new String[records.size()];
        var errors = new Throwable[records.size()];
        runItems(event, func, itemType, inSerdes, outSerdes, concurrency, outputs, errors);

        var results = new ArrayList<ItemResult>();
        var failures = new ArrayList<ItemFailure>();
        for (var i = 0; i < records.size(); i++) {
            var itemId = records.get(i).itemId();
            if (errors[i] != null) {
                var failure = new ItemFailure(itemId, DistributedMapError.of(errors[i]));
                logItemFailure(event.distributedMapRunArn(), failure, LOGGER::error);
                failures.add(failure);
            } else {
                results.add(new ItemResult(itemId, outputs[i]));
            }
        }
        return new ItemHandlerResponse(results, failures);
    }

    // Runs each record on a pool and stores the record's output or error at the record's index.
    private static <I, O> void runItems(
            ProcessorEvent event,
            BiFunction<I, ItemContext, O> func,
            TypeToken<I> itemType,
            SerDes inSerdes,
            SerDes outSerdes,
            int concurrency,
            String[] outputs,
            Throwable[] errors) {
        var records = event.records();
        var pool = Executors.newFixedThreadPool(concurrency);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var i = 0; i < records.size(); i++) {
                var index = i;
                futures.add(pool.submit(() -> {
                    var record = records.get(index);
                    I item = inSerdes.deserialize(record.body(), itemType);
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
            throw new IllegalStateException("Distributed map item helper interrupted", e);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Calls {@code func} on each item in the batch under its own durable context, and returns each item's result or
     * error in the format the service expects, running one item at a time.
     *
     * <p>Call it in the handler of your durable processor Lambda, and pass that Lambda's name to
     * {@code DistributedMapProcessor.reportItemOutcomes} in the parent's distributed map call.
     *
     * <p>Note: your handler must return this method's result, because the service reads which items failed from the
     * handler's return value. If the handler returns nothing, the service marks every item as succeeded.
     *
     * <p>It logs one ERROR line per failed item, with the run ARN, the item id and the reported error.
     *
     * <p>Pass the handler's {@link DurableContext} first. Each item runs through {@code context.map}, with
     * {@link NestingType#FLAT} by default.
     *
     * <pre>{@code
     * public class OrderProcessor extends DurableHandler<ProcessorEvent, ItemHandlerResponse> {
     *     public ItemHandlerResponse handleRequest(ProcessorEvent event, DurableContext context) {
     *         return DistributedMapHelpers.processDistributedMapItems(
     *                 context, event,
     *                 (durableCtx, order, itemContext) -> durableCtx.step("charge", String.class, s -> chargeOrder(order)),
     *                 TypeToken.get(Order.class), null, null);
     *     }
     * }
     * }</pre>
     *
     * @param context the handler's durable context
     * @param event the processor event the service sent
     * @param func the function to call on each item
     * @param itemType the item type
     * @param itemSerDes the serdes that reads each item, or null for the default JSON serdes
     * @param resultSerDes the serdes that writes each output, or null for the default JSON serdes
     * @return the response the handler must return
     */
    public static <I, O> ItemHandlerResponse processDistributedMapItems(
            DurableContext context,
            ProcessorEvent event,
            DurableItemFunction<I, O> func,
            TypeToken<I> itemType,
            SerDes itemSerDes,
            SerDes resultSerDes) {
        return processDistributedMapItems(
                context, event, func, itemType, itemSerDes, resultSerDes, DEFAULT_CONCURRENCY, NestingType.FLAT);
    }

    /**
     * Calls {@code func} on each item in the batch under its own durable context, and returns each item's result or
     * error in the format the service expects, with {@link NestingType#FLAT} nesting.
     *
     * <p>Call it in the handler of your durable processor Lambda, and pass that Lambda's name to
     * {@code DistributedMapProcessor.reportItemOutcomes} in the parent's distributed map call.
     *
     * <p>Note: your handler must return this method's result, because the service reads which items failed from the
     * handler's return value. If the handler returns nothing, the service marks every item as succeeded.
     *
     * <p>It logs one ERROR line per failed item, with the run ARN, the item id and the reported error.
     *
     * <pre>{@code
     * public class OrderProcessor extends DurableHandler<ProcessorEvent, ItemHandlerResponse> {
     *     public ItemHandlerResponse handleRequest(ProcessorEvent event, DurableContext context) {
     *         return DistributedMapHelpers.processDistributedMapItems(
     *                 context, event,
     *                 (durableCtx, order, itemContext) -> durableCtx.step("charge", String.class, s -> chargeOrder(order)),
     *                 TypeToken.get(Order.class), null, null, 5);
     *     }
     * }
     * }</pre>
     *
     * @param context the handler's durable context
     * @param event the processor event the service sent
     * @param func the function to call on each item
     * @param itemType the item type
     * @param itemSerDes the serdes that reads each item, or null for the default JSON serdes
     * @param resultSerDes the serdes that writes each output, or null for the default JSON serdes
     * @param concurrency the maximum number of items processed at the same time (default: 1), at least 1. When greater
     *     than 1, {@code func}, {@code itemSerDes} and {@code resultSerDes} are called from several threads at the same
     *     time, so each must be safe for concurrent calls.
     * @return the response the handler must return
     */
    public static <I, O> ItemHandlerResponse processDistributedMapItems(
            DurableContext context,
            ProcessorEvent event,
            DurableItemFunction<I, O> func,
            TypeToken<I> itemType,
            SerDes itemSerDes,
            SerDes resultSerDes,
            int concurrency) {
        return processDistributedMapItems(
                context, event, func, itemType, itemSerDes, resultSerDes, concurrency, NestingType.FLAT);
    }

    /**
     * Calls {@code func} on each item in the batch under its own durable context, and returns each item's result or
     * error in the format the service expects.
     *
     * <p>Call it in the handler of your durable processor Lambda, and pass that Lambda's name to
     * {@code DistributedMapProcessor.reportItemOutcomes} in the parent's distributed map call.
     *
     * <p>Note: your handler must return this method's result, because the service reads which items failed from the
     * handler's return value. If the handler returns nothing, the service marks every item as succeeded.
     *
     * <p>It logs one ERROR line per failed item, with the run ARN, the item id and the reported error.
     *
     * <pre>{@code
     * public class OrderProcessor extends DurableHandler<ProcessorEvent, ItemHandlerResponse> {
     *     public ItemHandlerResponse handleRequest(ProcessorEvent event, DurableContext context) {
     *         return DistributedMapHelpers.processDistributedMapItems(
     *                 context, event,
     *                 (durableCtx, order, itemContext) -> durableCtx.step("charge", String.class, s -> chargeOrder(order)),
     *                 TypeToken.get(Order.class), null, null, 5, NestingType.NESTED);
     *     }
     * }
     * }</pre>
     *
     * @param context the handler's durable context
     * @param event the processor event the service sent
     * @param func the function to call on each item
     * @param itemType the item type
     * @param itemSerDes the serdes that reads each item, or null for the default JSON serdes
     * @param resultSerDes the serdes that writes each output, or null for the default JSON serdes
     * @param concurrency the maximum number of items processed at the same time (default: 1), at least 1. When greater
     *     than 1, {@code func}, {@code itemSerDes} and {@code resultSerDes} are called from several threads at the same
     *     time, so each must be safe for concurrent calls.
     * @param nestingType the nesting type for the map iterations (default: {@link NestingType#FLAT})
     * @return the response the handler must return
     */
    public static <I, O> ItemHandlerResponse processDistributedMapItems(
            DurableContext context,
            ProcessorEvent event,
            DurableItemFunction<I, O> func,
            TypeToken<I> itemType,
            SerDes itemSerDes,
            SerDes resultSerDes,
            int concurrency,
            NestingType nestingType) {
        validateConcurrency(concurrency);
        var inSerdes = itemSerDes != null ? itemSerDes : JSON;
        var outSerdes = resultSerDes != null ? resultSerDes : JSON;
        var records = event.records();
        var bodies = new ArrayList<String>(records.size());
        for (var record : records) {
            bodies.add(record.body());
        }
        // Every item runs to completion, so one failing item never cancels another that would succeed.
        MapResult<String> batch = context.map(
                ITEMS_OP_NAME,
                bodies,
                OUTPUT_TYPE,
                (body, index, mapContext) -> {
                    var itemContext = new ItemContext(
                            event.distributedMapRunArn(), records.get(index).itemId());
                    O output = func.apply(mapContext, inSerdes.deserialize(body, itemType), itemContext);
                    return toOutput(outSerdes, output);
                },
                MapConfig.builder()
                        .completionConfig(CompletionConfig.allCompleted())
                        .maxConcurrency(concurrency)
                        .nestingType(nestingType)
                        .build());
        return toItemHandlerResponse(
                event, batch, message -> context.getLogger().error(message));
    }

    /**
     * Calls {@code func} once with all the items in the batch. Returning passes the batch, and throwing fails it.
     *
     * <p>Call it in the handler of your processor Lambda, and pass that Lambda's name to
     * {@code DistributedMapProcessor.reportBatchOutcome} in the parent's distributed map call.
     *
     * <p>Note: your handler must return this method's result and must not catch its exception, because the service
     * reads a batch failure from the handler's error. If the handler catches the exception, the failure is lost and the
     * service marks the batch as succeeded.
     *
     * <p>It works the same in a non-durable handler and in a durable handler. In a durable handler, use the handler's
     * own {@link DurableContext} inside {@code func}. This method does not pass it to {@code func}.
     *
     * <pre>{@code
     * public class RowWriter implements RequestHandler<ProcessorEvent, Void> {
     *     public Void handleRequest(ProcessorEvent event, Context context) {
     *         return DistributedMapHelpers.processDistributedMapBatch(
     *                 event, (List<Row> rows, BatchContext batchContext) -> writeRows(rows),
     *                 TypeToken.get(Row.class), null);
     *     }
     * }
     * }</pre>
     *
     * @param event the processor event the service sent
     * @param func the function to call once with all the items
     * @param itemType the item type
     * @param itemSerDes the serdes that reads each item, or null for the default JSON serdes
     * @return null, which the handler must return
     */
    public static <I> Void processDistributedMapBatch(
            ProcessorEvent event, BiConsumer<List<I>, BatchContext> func, TypeToken<I> itemType, SerDes itemSerDes) {
        var serdes = itemSerDes != null ? itemSerDes : JSON;
        var records = event.records();
        var items = new ArrayList<I>(records.size());
        for (var record : records) {
            items.add(serdes.deserialize(record.body(), itemType));
        }
        func.accept(
                items,
                new BatchContext(
                        event.distributedMapRunArn(),
                        records.stream().map(ProcessorRecord::itemId).toList()));
        return null;
    }

    /**
     * Calls {@code func} to read one page of items, and returns the page in the format the service expects. A null next
     * state means the source has no more items.
     *
     * <p>Call it in the handler of your reader Lambda, and pass that Lambda's name to
     * {@code DistributedMapSource.reader} in the parent's distributed map call.
     *
     * <p>Note: your handler must return this method's result, because the service reads the page from the handler's
     * return value.
     *
     * <p>{@code itemSerDes} must be the serdes the processor is configured with, since the processor deserializes every
     * item body with its own item serdes. The reader and the processor are separate Lambdas, so nothing checks that
     * they match.
     *
     * <pre>{@code
     * public class CustomerReader implements RequestHandler<ReaderEvent, ReaderResponse> {
     *     public ReaderResponse handleRequest(ReaderEvent event, Context context) {
     *         return DistributedMapHelpers.readDistributedMapPage(
     *                 event, (String cursor, Integer maxItemsPerPage, ReaderContext readerContext) -> {
     *                     var page = listCustomers(cursor, maxItemsPerPage);
     *                     return new ReaderPage<>(page.customers(), page.nextCursor());
     *                 },
     *                 TypeToken.get(String.class), null, null);
     *     }
     * }
     * }</pre>
     *
     * @param event the reader event the service sent
     * @param func the function that reads one page
     * @param stateType the state type
     * @param stateSerDes the serdes for the state, or null for the default JSON serdes
     * @param itemSerDes the serdes that writes each item, or null for the default JSON serdes
     * @return the response the handler must return
     */
    public static <I, S> ReaderResponse readDistributedMapPage(
            ReaderEvent event,
            ReaderFunction<I, S> func,
            TypeToken<S> stateType,
            SerDes stateSerDes,
            SerDes itemSerDes) {
        var serdes = stateSerDes != null ? stateSerDes : JSON;
        var itemSerdes = itemSerDes != null ? itemSerDes : JSON;
        S state = event.state() != null ? serdes.deserialize(event.state(), stateType) : null;

        var page = func.apply(state, event.maxItemsPerPage(), new ReaderContext(event.distributedMapRunArn()));
        if (page.items().size() > event.maxItemsPerPage()) {
            throw new IllegalStateException("reader returned " + page.items().size()
                    + " items, exceeding maxItemsPerPage " + event.maxItemsPerPage());
        }
        var items = new ArrayList<String>(page.items().size());
        var index = 0;
        for (I item : page.items()) {
            var body = itemSerdes.serialize(item);
            if (body == null) {
                // The service requires every item to be a string, so a null item is sent as the JSON literal null.
                if (item != null) {
                    throw new IllegalStateException("serialization of reader item " + index + " produced no string");
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
            // The next call would repeat the same position, and the service rejects it as a source failure.
            if (nextState.equals(event.state())) {
                throw new IllegalStateException(
                        "reader nextState must differ from the state it was called with, or the next page repeats the same position");
            }
        }
        return new ReaderResponse(items, nextState);
    }

    private static void validateConcurrency(int concurrency) {
        if (concurrency < MIN_CONCURRENCY) {
            throw new IllegalArgumentException(
                    "concurrency must be at least " + MIN_CONCURRENCY + ", got: " + concurrency);
        }
    }

    private static ItemHandlerResponse toItemHandlerResponse(
            ProcessorEvent event, MapResult<String> batch, Consumer<String> logError) {
        var records = event.records();
        var results = new ArrayList<ItemResult>();
        var failures = new ArrayList<ItemFailure>();
        for (var i = 0; i < records.size(); i++) {
            var itemId = records.get(i).itemId();
            var item = batch.getItem(i);
            if (item.status() == MapResult.MapResultItem.Status.SUCCEEDED) {
                results.add(new ItemResult(itemId, item.result()));
            } else {
                var error = item.error();
                var failure = new ItemFailure(
                        itemId,
                        error != null
                                ? new DistributedMapError(
                                        error.errorType(), error.errorMessage(), null, error.stackTrace())
                                : new DistributedMapError(null, "item " + itemId + " did not complete", null, null));
                logItemFailure(event.distributedMapRunArn(), failure, logError);
                failures.add(failure);
            }
        }
        return new ItemHandlerResponse(results, failures);
    }

    // Puts the failure's fields in the MDC for the one log call, then removes exactly those keys.
    static void logItemFailure(String runArn, ItemFailure failure, Consumer<String> logError) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("distributedMapRunArn", runArn);
        fields.put("itemId", failure.itemIdentifier());
        var error = failure.error();
        if (error != null) {
            fields.put("errorType", error.errorType());
            fields.put("errorMessage", error.errorMessage());
            fields.put("errorData", error.errorData());
            fields.put("stackTrace", error.stackTrace() != null ? String.join("\n", error.stackTrace()) : null);
        }
        fields.values().removeIf(Objects::isNull);
        fields.forEach(MDC::put);
        try {
            logError.accept("distributed map item failed");
        } finally {
            fields.keySet().forEach(MDC::remove);
        }
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
