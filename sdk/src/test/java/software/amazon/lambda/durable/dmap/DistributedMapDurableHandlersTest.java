// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Tests that the durable processor wrappers run under the durable execution config the caller supplies. The config is
 * how a durable processor registers plugins or supplies its own client, so the wrapper must not force the default.
 */
class DistributedMapDurableHandlersTest {
    private static final String EXECUTION_OP_ID = "f3d7b0c0-1234-5678-90ab-cdef12345678";
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test" + "/durable-execution/exec-name/" + EXECUTION_OP_ID;
    private static final String RUN_ARN = "arn:aws:lambda:us-east-1:123456789012:distributed-map-run:run-1";

    /** Counts deserialize calls, so a test can prove the config's own serdes read the event. */
    private static final class RecordingSerDes implements SerDes {
        private final SerDes delegate = new JacksonSerDes();
        private final AtomicInteger deserializeCount = new AtomicInteger();

        @Override
        public String serialize(Object value) {
            return delegate.serialize(value);
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            deserializeCount.incrementAndGet();
            return delegate.deserialize(data, typeToken);
        }
    }

    private static DurableConfig configWith(SerDes serDes) {
        return DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withSerDes(serDes)
                .build();
    }

    // TestUtils.createMockClient echoes START and SUCCEED only. This client also echoes a failed child context.
    private static DurableExecutionClient clientEchoingContextFailures() {
        var delegate = TestUtils.createMockClient();
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var response = delegate.checkpoint(invocation.getArgument(0), invocation.getArgument(1), updates);
            var operations = new ArrayList<Operation>();
            // The delegate returns one operation per update, in update order.
            for (var i = 0; i < updates.size(); i++) {
                var operation = response.newExecutionState().operations().get(i);
                var update = updates.get(i);
                if (update.action() == OperationAction.FAIL && update.type() == OperationType.CONTEXT) {
                    operations.add(operation.toBuilder()
                            .status(OperationStatus.FAILED)
                            .contextDetails(ContextDetails.builder()
                                    .error(update.error())
                                    .build())
                            .build());
                } else {
                    operations.add(operation);
                }
            }
            return response.toBuilder()
                    .newExecutionState(CheckpointUpdatedExecutionState.builder()
                            .operations(operations)
                            .build())
                    .build();
        });
        return client;
    }

    // A first invocation of a durable function: one STARTED execution operation carrying the processor batch.
    private static DurableExecutionInput durableInput(ProcessorEvent event) {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.parse("2026-08-15T00:00:00Z"))
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload(new JacksonSerDes().serialize(event))
                        .build())
                .build();
        return new DurableExecutionInput(
                EXECUTION_ARN,
                "token-1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());
    }

    private static ProcessorEvent event(String... bodies) {
        var records = new java.util.ArrayList<ProcessorRecord>();
        for (var i = 0; i < bodies.length; i++) {
            records.add(new ProcessorRecord(String.valueOf(i), bodies[i]));
        }
        return new ProcessorEvent(RUN_ARN, records);
    }

    @Test
    void durableBatchHandlerRunsUnderTheSuppliedConfig() {
        var serDes = new RecordingSerDes();
        var seen = new java.util.ArrayList<Integer>();
        DistributedMapHandlers.DurableBatchFunction<Integer> func = (ctx, items, batchCtx) ->
                seen.addAll(ctx.step("copy", new TypeToken<List<Integer>>() {}, stepCtx -> items));

        var handler = DistributedMapHandlers.createDistributedMapBatchHandlerWithDurableExecution(
                func, TypeToken.get(Integer.class), null, configWith(serDes));
        var output = handler.handleRequest(durableInput(event("1", "2")), null);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(List.of(1, 2), seen);
        // The processor's event is always read with the default JSON SerDes. The step inside the processor uses the
        // config's SerDes, so a call here means the supplied config reached the execution.
        assertTrue(serDes.deserializeCount.get() > 0);
    }

    @Test
    void durableItemHandlerRunsUnderTheSuppliedConfig() {
        var serDes = new RecordingSerDes();
        DistributedMapHandlers.DurableItemFunction<Integer, Integer> func = (ctx, item, itemCtx) -> item * 2;

        var handler = DistributedMapHandlers.createDistributedMapItemHandlerWithDurableExecution(
                func, TypeToken.get(Integer.class), null, null, configWith(serDes));
        var output = handler.handleRequest(durableInput(event("1", "2")), null);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(serDes.deserializeCount.get() > 0);
    }

    @Test
    void durableItemHandlerPassesEachItemsOwnId() {
        var seen = new ConcurrentHashMap<Integer, ItemContext>();
        DistributedMapHandlers.DurableItemFunction<Integer, Integer> func = (ctx, item, itemCtx) -> {
            seen.put(item, itemCtx);
            return item;
        };
        var event = new ProcessorEvent(RUN_ARN, List.of(new ProcessorRecord("a", "1"), new ProcessorRecord("b", "2")));

        var handler = DistributedMapHandlers.createDistributedMapItemHandlerWithDurableExecution(
                func, TypeToken.get(Integer.class), null, null, configWith(new JacksonSerDes()));
        var output = handler.handleRequest(durableInput(event), null);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(Map.of(1, new ItemContext(RUN_ARN, "a"), 2, new ItemContext(RUN_ARN, "b")), seen);
    }

    @Test
    void durableBatchHandlerPassesIdsAlignedWithItems() {
        var seenItems = new ArrayList<Integer>();
        var seenContext = new ArrayList<BatchContext>();
        DistributedMapHandlers.DurableBatchFunction<Integer> func = (ctx, items, batchCtx) -> {
            seenItems.addAll(items);
            seenContext.add(batchCtx);
        };
        var event = new ProcessorEvent(RUN_ARN, List.of(new ProcessorRecord("a", "1"), new ProcessorRecord("b", "2")));

        var handler = DistributedMapHandlers.createDistributedMapBatchHandlerWithDurableExecution(
                func, TypeToken.get(Integer.class), null, configWith(new JacksonSerDes()));
        var output = handler.handleRequest(durableInput(event), null);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(List.of(1, 2), seenItems);
        assertEquals(List.of(new BatchContext(RUN_ARN, List.of("a", "b"))), seenContext);
    }

    // The service defines the processor's event and response format, so a custom SerDes on the config must not
    // rename the envelope's fields.
    @Test
    void customSerDesDoesNotChangeTheProcessorEnvelope() {
        var snakeCase = new JacksonSerDes(new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        DistributedMapHandlers.DurableItemFunction<Integer, Integer> func = (ctx, item, itemCtx) -> item * 2;

        var handler = DistributedMapHandlers.createDistributedMapItemHandlerWithDurableExecution(
                func, TypeToken.get(Integer.class), null, null, configWith(snakeCase));
        var output = handler.handleRequest(durableInput(event("1")), null);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(output.result().contains("\"batchItemResults\""), output.result());
        assertTrue(output.result().contains("\"itemIdentifier\":\"0\""), output.result());
        assertTrue(output.result().contains("\"batchItemFailures\""), output.result());
    }

    @Test
    void durableItemHandlerFailsItemWhoseOutputSerializesToNull() {
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
        DistributedMapHandlers.DurableItemFunction<Integer, Integer> func = (ctx, item, itemCtx) -> item;

        var handler = DistributedMapHandlers.createDistributedMapItemHandlerWithDurableExecution(
                func,
                TypeToken.get(Integer.class),
                null,
                nullSerDes,
                DurableConfig.builder()
                        .withDurableExecutionClient(clientEchoingContextFailures())
                        .build());
        var output = handler.handleRequest(durableInput(event("1")), null);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        var response = new JacksonSerDes().deserialize(output.result(), TypeToken.get(ItemHandlerResponse.class));
        assertEquals(List.of(), response.batchItemResults());
        var failure = response.batchItemFailures().get(0);
        assertEquals("0", failure.itemIdentifier());
        assertEquals(
                "serialization of item output produced no string",
                failure.error().errorMessage());
    }
}
