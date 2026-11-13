// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.dmap.DistributedMapHandlers.*;

import com.amazonaws.services.lambda.runtime.serialization.events.LambdaEventSerializers;
import com.amazonaws.services.lambda.runtime.serialization.factories.JacksonFactory;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DistributedMapError;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/** Unit tests for the distributed map authoring helpers (non-durable variants). */
class DistributedMapHandlersTest {
    private static final String RUN_ARN = "arn:aws:lambda:us-east-1:123456789012:distributed-map-run:run-1";

    // Builds a single processor record. The body is the serialized item, as the wire carries it.
    private static ProcessorRecord rec(String itemId, String body) {
        return new ProcessorRecord(itemId, body);
    }

    // Builds a distributed map processor event carrying the given records.
    private static ProcessorEvent processorEvent(ProcessorRecord... records) {
        return new ProcessorEvent(RUN_ARN, List.of(records));
    }

    // Builds a distributed map reader event carrying the state and maxItemsPerPage fields.
    private static ReaderEvent readerEvent(String state, int maxItemsPerPage) {
        return new ReaderEvent(RUN_ARN, maxItemsPerPage, state);
    }

    private static DistributedMapError error(String type, String message) {
        return new DistributedMapError(type, message, null, null);
    }

    private static void assertFailure(ItemFailure failure, String itemId, String type, String message) {
        assertEquals(itemId, failure.itemIdentifier());
        assertEquals(type, failure.error().errorType());
        assertEquals(message, failure.error().errorMessage());
        assertNull(failure.error().errorData());
        assertFalse(failure.error().stackTrace().isEmpty());
    }

    /** Serdes whose wire form is not JSON, so an item that skipped serialization cannot be read back. */
    private static final class PrefixSerDes implements SerDes {
        @Override
        public String serialize(Object value) {
            return "p:" + value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            if (!data.startsWith("p:")) {
                throw new IllegalArgumentException("not a prefixed value: " + data);
            }
            return (T) Integer.valueOf(data.substring(2));
        }
    }

    @Test
    void itemHandlerReportsResultsInOrder() {
        BiFunction<Integer, ItemContext, Integer> func = (x, itemCtx) -> x * 2;
        var handler = createDistributedMapItemHandler(func, TypeToken.get(Integer.class), null, null, 1);

        var resp = handler.handleRequest(processorEvent(rec("0", "2"), rec("1", "3")), null);

        assertEquals(List.of(new ItemResult("0", "4"), new ItemResult("1", "6")), resp.batchItemResults());
        assertEquals(List.of(), resp.batchItemFailures());
    }

    @Test
    void itemHandlerCapturesFailuresWithFullyQualifiedErrorType() {
        BiFunction<String, ItemContext, String> func = (x, itemCtx) -> {
            if (x.equals("bad")) {
                throw new IllegalStateException("boom");
            }
            return x;
        };
        var handler = createDistributedMapItemHandler(func, TypeToken.get(String.class), null, null, 1);

        var resp = handler.handleRequest(processorEvent(rec("0", "\"ok\""), rec("1", "\"bad\"")), null);

        assertEquals(List.of(new ItemResult("0", "\"ok\"")), resp.batchItemResults());
        assertEquals(1, resp.batchItemFailures().size());
        assertFailure(resp.batchItemFailures().get(0), "1", "java.lang.IllegalStateException", "boom");
    }

    @Test
    void itemHandlerReportsAnEmptyResultsListWhenEveryItemFails() {
        BiFunction<Integer, ItemContext, Integer> func = (x, itemCtx) -> {
            throw new IllegalStateException("nope");
        };
        var handler = createDistributedMapItemHandler(func, TypeToken.get(Integer.class), null, null, 1);

        var resp = handler.handleRequest(processorEvent(rec("7", "1")), null);

        // Both lists are always reported, so an all-failing batch carries an empty results list rather than no key.
        assertEquals(List.of(), resp.batchItemResults());
        assertEquals(1, resp.batchItemFailures().size());
        assertFailure(resp.batchItemFailures().get(0), "7", "java.lang.IllegalStateException", "nope");
    }

    @Test
    void itemHandlerHonorsRequestedConcurrency() {
        // Each item blocks until all four have arrived, so the batch only finishes if the pool really runs four
        // items at once. At a smaller pool size the first item waits out the timeout and every item fails.
        var barrier = new CyclicBarrier(4);
        BiFunction<Integer, ItemContext, Integer> func = (x, itemCtx) -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("barrier not reached", e);
            }
            return x;
        };
        var handler = createDistributedMapItemHandler(func, TypeToken.get(Integer.class), null, null, 4);

        var resp =
                handler.handleRequest(processorEvent(rec("0", "0"), rec("1", "1"), rec("2", "2"), rec("3", "3")), null);

        assertEquals(List.of(), resp.batchItemFailures());
        // The four items are released together, so completion order is arbitrary while results stay in item order.
        assertEquals(
                List.of(
                        new ItemResult("0", "0"),
                        new ItemResult("1", "1"),
                        new ItemResult("2", "2"),
                        new ItemResult("3", "3")),
                resp.batchItemResults());
    }

    @Test
    void itemHandlerDefaultsToOneConcurrentHandler() {
        var inFlight = new AtomicInteger();
        var peakInFlight = new AtomicInteger();
        BiFunction<String, ItemContext, String> func = (x, itemCtx) -> {
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                // Held briefly so a regression to a wider pool would actually overlap and be caught.
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return x;
        };
        // The overload with no concurrency argument runs one handler at a time.
        var handler = createDistributedMapItemHandler(func, TypeToken.get(String.class), null, null);

        var resp = handler.handleRequest(processorEvent(rec("0", "\"a\""), rec("1", "\"b\""), rec("2", "\"c\"")), null);

        assertEquals(1, peakInFlight.get());
        assertEquals(3, resp.batchItemResults().size());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void itemHandlerRejectsNonPositiveConcurrency(int concurrency) {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapHandlers.<String, String>createDistributedMapItemHandler(
                        (item, itemCtx) -> item, TypeToken.get(String.class), null, null, concurrency));
        assertEquals("concurrency must be at least 1, got: " + concurrency, exception.getMessage());
    }

    @Test
    void itemHandlerReportsSerializationFailureAgainstTheOffendingItemOnly() {
        // A result serdes that refuses exactly one value, standing in for a POJO the caller cannot serialize.
        var refusingSerDes = new SerDes() {
            private final SerDes delegate = new JacksonSerDes();

            @Override
            public String serialize(Object value) {
                if ("boom".equals(value)) {
                    throw new IllegalStateException("cannot serialize");
                }
                return delegate.serialize(value);
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return delegate.deserialize(data, typeToken);
            }
        };

        var handler = DistributedMapHandlers.<String, String>createDistributedMapItemHandler(
                (item, itemCtx) -> item, TypeToken.get(String.class), null, refusingSerDes, 2);

        var resp = handler.handleRequest(
                processorEvent(rec("a", "\"ok\""), rec("b", "\"boom\""), rec("c", "\"fine\"")), null);

        // The whole batch must not fail. Only item b is reported as failed.
        assertEquals(2, resp.batchItemResults().size());
        assertEquals("a", resp.batchItemResults().get(0).itemIdentifier());
        assertEquals("c", resp.batchItemResults().get(1).itemIdentifier());
        assertEquals(1, resp.batchItemFailures().size());
        assertEquals("b", resp.batchItemFailures().get(0).itemIdentifier());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void itemHandlerPassesTheRunArnAndEachItemsOwnId(int concurrency) {
        var seen = new ConcurrentHashMap<Integer, ItemContext>();
        BiFunction<Integer, ItemContext, Integer> func = (x, itemCtx) -> {
            seen.put(x, itemCtx);
            return x;
        };
        var handler = createDistributedMapItemHandler(func, TypeToken.get(Integer.class), null, null, concurrency);

        handler.handleRequest(processorEvent(rec("a", "1"), rec("b", "2"), rec("c", "3")), null);

        assertEquals(
                Map.of(
                        1, new ItemContext(RUN_ARN, "a"),
                        2, new ItemContext(RUN_ARN, "b"),
                        3, new ItemContext(RUN_ARN, "c")),
                seen);
    }

    @Test
    void batchHandlerPassesTheRunArnAndIdsAlignedWithItems() {
        var seenItems = new ArrayList<Integer>();
        var seenContext = new ArrayList<BatchContext>();
        BiConsumer<List<Integer>, BatchContext> func = (items, batchCtx) -> {
            seenItems.addAll(items);
            seenContext.add(batchCtx);
        };
        var handler = createDistributedMapBatchHandler(func, TypeToken.get(Integer.class), null);

        handler.handleRequest(processorEvent(rec("a", "1"), rec("b", "2"), rec("c", "3")), null);

        assertEquals(List.of(1, 2, 3), seenItems);
        assertEquals(List.of(new BatchContext(RUN_ARN, List.of("a", "b", "c"))), seenContext);
    }

    @Test
    void readerPassesTheRunArn() {
        var seen = new ArrayList<ReaderContext>();
        ReaderFunction<Integer, Integer> read = (state, maxItemsPerPage, readerCtx) -> {
            seen.add(readerCtx);
            return new ReaderPage<>(List.of(), null);
        };
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, null);

        handler.handleRequest(readerEvent(null, 10), null);

        assertEquals(List.of(new ReaderContext(RUN_ARN)), seen);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void processorEventWithoutRunArnIsRejected(String runArn) {
        var exception = assertThrows(IllegalStateException.class, () -> new ProcessorEvent(runArn, List.of()));
        assertEquals(
                "expected a distributed map processor event to have a 'distributedMapRunArn'", exception.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void readerEventWithoutRunArnIsRejected(String runArn) {
        var exception = assertThrows(IllegalStateException.class, () -> new ReaderEvent(runArn, 10, null));
        assertEquals(
                "expected a distributed map reader event to have a 'distributedMapRunArn'", exception.getMessage());
    }

    @Test
    void processorEventJsonCarriesTheRunArn() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"records\":[{\"itemId\":\"a\",\"body\":\"1\"}]}";

        var event = new JacksonSerDes().deserialize(json, TypeToken.get(ProcessorEvent.class));

        assertEquals(processorEvent(rec("a", "1")), event);
    }

    @Test
    void readerEventJsonCarriesTheRunArn() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"maxItemsPerPage\":10,\"state\":\"s\"}";

        var event = new JacksonSerDes().deserialize(json, TypeToken.get(ReaderEvent.class));

        assertEquals(readerEvent("s", 10), event);
    }

    // Deserializes the way the Lambda Java runtime does for a RequestHandler whose input type is a POJO outside the
    // runtime's supported event classes: JacksonFactory.getSerializer(Type), then fromJson(InputStream).
    private static Object deserializeAsLambdaRuntime(String json, Class<?> type) {
        assertFalse(LambdaEventSerializers.isLambdaSupportedEvent(type.getName()));
        var serializer = JacksonFactory.getInstance().getSerializer((Type) type);
        return serializer.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void lambdaRuntimeReadsTheRunArnIntoProcessorEvent() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"records\":[{\"itemId\":\"a\",\"body\":\"1\"}]}";

        var event = deserializeAsLambdaRuntime(json, ProcessorEvent.class);

        assertEquals(processorEvent(rec("a", "1")), event);
    }

    @Test
    void lambdaRuntimeReadsTheRunArnIntoReaderEvent() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"maxItemsPerPage\":10,\"state\":\"s\"}";

        var event = deserializeAsLambdaRuntime(json, ReaderEvent.class);

        assertEquals(readerEvent("s", 10), event);
    }

    @Test
    void lambdaRuntimeRejectsReaderEventWithoutRunArn() {
        var json = "{\"maxItemsPerPage\":10,\"state\":\"s\"}";

        // The serializer wraps Jackson's exception, which wraps the IllegalStateException from the record constructor.
        var exception =
                assertThrows(UncheckedIOException.class, () -> deserializeAsLambdaRuntime(json, ReaderEvent.class));

        var constructorFailure = exception.getCause().getCause();
        assertInstanceOf(IllegalStateException.class, constructorFailure);
        assertEquals(
                "expected a distributed map reader event to have a 'distributedMapRunArn'",
                constructorFailure.getMessage());
    }

    @Test
    void processorEventRejectsMissingRecords() {
        var ex = assertThrows(IllegalStateException.class, () -> new ProcessorEvent(RUN_ARN, null));
        assertTrue(ex.getMessage().contains("'records' array"));
        assertTrue(ex.getMessage().contains("not invoked directly"));
    }

    @Test
    void readerEventRejectsMissingMaxItemsPerPage() {
        var ex = assertThrows(IllegalStateException.class, () -> new ReaderEvent(RUN_ARN, null, "state"));
        assertTrue(ex.getMessage().contains("'maxItemsPerPage'"));
    }

    @Test
    void batchHandlerSucceedsAllItemsWhenFunctionReturns() {
        var seen = new ArrayList<Integer>();
        BiConsumer<List<Integer>, BatchContext> func = (items, batchCtx) -> seen.addAll(items);
        var handler = createDistributedMapBatchHandler(func, TypeToken.get(Integer.class), null);

        var resp = handler.handleRequest(processorEvent(rec("0", "1"), rec("1", "2")), null);

        assertNull(resp);
        assertEquals(List.of(1, 2), seen);
    }

    @Test
    void batchHandlerFailsBatchWhenFunctionThrows() {
        BiConsumer<List<Integer>, BatchContext> func = (items, batchCtx) -> {
            throw new RuntimeException("batch failed");
        };
        var handler = createDistributedMapBatchHandler(func, TypeToken.get(Integer.class), null);

        var ex = assertThrows(RuntimeException.class, () -> handler.handleRequest(processorEvent(rec("0", "1")), null));
        assertTrue(ex.getMessage().contains("batch failed"));
    }

    @Test
    void readerReturnsItemsAndNextStateThenExhausts() {
        ReaderFunction<Integer, Integer> read = (state, maxItemsPerPage, readerCtx) ->
                state == null ? new ReaderPage<>(List.of(1, 2), 1) : new ReaderPage<>(List.of(3), null);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, null);

        var first = handler.handleRequest(readerEvent(null, 10), null);
        assertEquals(List.of("1", "2"), first.items());
        assertEquals("1", first.nextState());

        var second = handler.handleRequest(readerEvent("1", 10), null);
        assertEquals(List.of("3"), second.items());
        // Null rather than empty, so the runtime omits the key, which is how exhaustion is signalled.
        assertNull(second.nextState());
    }

    @Test
    void readerReceivesMaxItemsPerPage() {
        ReaderFunction<Integer, Integer> read =
                (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(maxItemsPerPage), null);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, null);

        var page = handler.handleRequest(readerEvent(null, 7), null);

        assertEquals(List.of("7"), page.items());
    }

    @Test
    void readerRejectsPageOverMaxItemsPerPage() {
        ReaderFunction<Integer, Integer> read =
                (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(1, 2, 3), null);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, null);

        var ex = assertThrows(IllegalStateException.class, () -> handler.handleRequest(readerEvent(null, 2), null));
        assertTrue(ex.getMessage().contains("exceeding maxItemsPerPage"));
    }

    @Test
    void readerRejectsOversizedNextState() {
        ReaderFunction<Integer, String> read =
                (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(1), "x".repeat(40_000));
        var handler = createDistributedMapReader(read, TypeToken.get(String.class), null, null);

        var ex = assertThrows(IllegalStateException.class, () -> handler.handleRequest(readerEvent(null, 10), null));
        assertTrue(ex.getMessage().contains("32 KB"));
    }

    @Test
    void readerSendsNullItemAsJsonNull() {
        ReaderFunction<Integer, Integer> read =
                (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(java.util.Arrays.asList(1, null), null);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, null);

        var page = handler.handleRequest(readerEvent(null, 10), null);

        assertEquals(List.of("1", "null"), page.items());
    }

    @Test
    void readerRejectsItemSerializedToNull() {
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
        ReaderFunction<Integer, Integer> read =
                (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(1), null);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, nullSerDes);

        var ex = assertThrows(IllegalStateException.class, () -> handler.handleRequest(readerEvent(null, 10), null));
        assertEquals("serialization of reader item 0 produced no string", ex.getMessage());
    }

    @Test
    void readerRejectsNextStateSerializedToNull() {
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
        ReaderFunction<Integer, Integer> read = (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(1), 1);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), nullSerDes, null);

        var ex = assertThrows(IllegalStateException.class, () -> handler.handleRequest(readerEvent(null, 10), null));
        assertEquals("serialization of reader nextState produced no string", ex.getMessage());
    }

    @Test
    void readerRejectsNextStateEqualToInputStateOnEmptyPage() {
        ReaderFunction<Integer, Integer> read = (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(), 5);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, null);

        var ex = assertThrows(IllegalStateException.class, () -> handler.handleRequest(readerEvent("5", 10), null));
        assertEquals(
                "reader nextState must differ from the state it was called with, or the next page repeats the same position",
                ex.getMessage());
    }

    @Test
    void readerRejectsNextStateEqualToInputStateOnNonEmptyPage() {
        ReaderFunction<Integer, Integer> read =
                (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(1, 2), state);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, null);

        var ex = assertThrows(IllegalStateException.class, () -> handler.handleRequest(readerEvent("5", 10), null));
        assertEquals(
                "reader nextState must differ from the state it was called with, or the next page repeats the same position",
                ex.getMessage());
    }

    @Test
    void readerAcceptsNextStateOnFirstPage() {
        ReaderFunction<Integer, Integer> read = (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(), 0);
        var handler = createDistributedMapReader(read, TypeToken.get(Integer.class), null, null);

        var page = handler.handleRequest(readerEvent(null, 10), null);

        assertEquals("0", page.nextState());
    }

    @Test
    void itemHandlerFailsItemWhoseOutputSerializesToNull() {
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
        BiFunction<Integer, ItemContext, Integer> func = (x, itemCtx) -> 1;
        var handler = createDistributedMapItemHandler(func, TypeToken.get(Integer.class), null, nullSerDes, 1);

        var resp = handler.handleRequest(processorEvent(rec("0", "1")), null);

        assertEquals(List.of(), resp.batchItemResults());
        assertFailure(
                resp.batchItemFailures().get(0),
                "0",
                "java.lang.IllegalStateException",
                "serialization of item output produced no string");
    }

    @Test
    void readerItemsRoundTripThroughTheProcessorUnderACustomItemSerdes() {
        var serdes = new PrefixSerDes();
        ReaderFunction<Integer, Integer> read =
                (state, maxItemsPerPage, readerCtx) -> new ReaderPage<>(List.of(1, 2), null);
        var reader = createDistributedMapReader(read, TypeToken.get(Integer.class), null, serdes);

        var page = reader.handleRequest(readerEvent(null, 10), null);
        assertEquals(List.of("p:1", "p:2"), page.items());

        var seen = new ArrayList<Integer>();
        var processor = createDistributedMapItemHandler(
                (BiFunction<Integer, ItemContext, Integer>) (item, itemCtx) -> {
                    seen.add(item);
                    return item;
                },
                TypeToken.get(Integer.class),
                serdes,
                null);
        processor.handleRequest(
                processorEvent(
                        rec("0", page.items().get(0)), rec("1", page.items().get(1))),
                null);

        assertEquals(List.of(1, 2), seen);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void durableItemHandlerRejectsNonPositiveConcurrency(int concurrency) {
        DurableItemFunction<Integer, Integer> func = (ctx, item, itemCtx) -> item;
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> createDistributedMapItemHandlerWithDurableExecution(
                        func, TypeToken.get(Integer.class), null, null, concurrency));
        assertEquals("concurrency must be at least 1, got: " + concurrency, exception.getMessage());
    }

    // The Lambda runtime serializes the response, so these assert the wire shape it produces. The mapper mirrors the
    // runtime's null exclusion, which is what keeps an absent key absent.
    private static final ObjectMapper WIRE = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void itemHandlerReportsNoOutputWhenTheHandlerReturnsNull() {
        // PrefixSerDes stringifies whatever it is handed, so an output is reported only if the handler
        // never calls it for an item that returned nothing.
        BiFunction<Integer, ItemContext, String> func = (x, itemCtx) -> null;
        var handler = createDistributedMapItemHandler(func, TypeToken.get(Integer.class), null, new PrefixSerDes(), 1);

        var resp = handler.handleRequest(processorEvent(rec("0", "1")), null);

        assertEquals(List.of(), resp.batchItemFailures());
        assertEquals(List.of(new ItemResult("0", null)), resp.batchItemResults());
    }

    @Test
    void itemResultWithNoOutputLeavesTheOutputOffTheWire() throws Exception {
        var json = WIRE.writeValueAsString(new ItemResult("0", null));

        assertEquals("{\"itemIdentifier\":\"0\"}", json);
    }

    @Test
    void itemFailureCarriesTheStackTraceAndNoErrorData() {
        BiFunction<Integer, ItemContext, Integer> func = (x, itemCtx) -> {
            throw new IllegalStateException("nope");
        };
        var handler = createDistributedMapItemHandler(func, TypeToken.get(Integer.class), null, null, 1);

        var error = handler.handleRequest(processorEvent(rec("0", "1")), null)
                .batchItemFailures()
                .get(0)
                .error();

        assertNull(error.errorData());
        assertTrue(error.stackTrace().get(0).startsWith(DistributedMapHandlersTest.class.getName()));
    }

    @Test
    void itemFailureNestsTheErrorObject() throws Exception {
        var json = WIRE.writeValueAsString(new ItemFailure("1", error("SomeType", "boom")));

        assertEquals(
                "{\"itemIdentifier\":\"1\",\"error\":{\"errorType\":\"SomeType\",\"errorMessage\":\"boom\"}}", json);
    }

    @Test
    void itemFailureWritesEveryErrorMember() throws Exception {
        var detail = new DistributedMapError("E", "boom", "{\"k\":1}", List.of("f1"));
        var json = WIRE.writeValueAsString(new ItemFailure("1", detail));

        assertEquals(
                "{\"itemIdentifier\":\"1\",\"error\":{\"errorType\":\"E\",\"errorMessage\":\"boom\","
                        + "\"errorData\":\"{\\\"k\\\":1}\",\"stackTrace\":[\"f1\"]}}",
                json);
    }

    @Test
    void exhaustedReaderPageLeavesNextStateOffTheWire() throws Exception {
        var json = WIRE.writeValueAsString(new ReaderResponse(List.of("1", "2"), null));

        assertEquals("{\"items\":[\"1\",\"2\"]}", json);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void processorRecordWithoutItemIdIsRejected(String itemId) {
        var exception = assertThrows(IllegalStateException.class, () -> new ProcessorRecord(itemId, "1"));
        assertEquals("expected each distributed map processor record to have an 'itemId'", exception.getMessage());
    }
}
