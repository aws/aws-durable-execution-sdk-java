// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.lambda.durable.model.ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED;
import static software.amazon.lambda.durable.model.OperationSubType.MAP;
import static software.amazon.lambda.durable.model.OperationSubType.MAP_ITERATION;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.exception.MapIterationFailedException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFailure;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class DurableMapOperationImplementationTest {
    @Test
    void executeBuildsMapAndIterationContextsFromReservations() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var parentFuture = mockMapFuture();
        var serDes = new JacksonSerDes();
        var config = DurableMapOperation.MapConfig.builder()
                .serDes(serDes)
                .nestingType(DurableConcurrencyOperation.NestingType.FLAT)
                .build();
        when(context.reserve("map")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(MAP.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(parentFuture);

        var actual = DurableMapOperation.mapAsync(
                context,
                "map",
                List.of("a", "b"),
                TypeToken.get(String.class),
                (item, index, child) -> item + index,
                config);

        assertSame(parentFuture, actual);
        var function = extensionFunction();
        var parentConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(parent)
                .runInChildContextAsync(
                        eq(MAP.getValue()), any(TypeToken.class), function.capture(), parentConfig.capture());
        assertSame(serDes, parentConfig.getValue().serDes());
        assertFalse(parentConfig.getValue().emitUserFunctionEvents());
        assertTrue(parentConfig.getValue().suppressLateChildCheckpoints());
        assertTrue(parentConfig.getValue().validateCompletedReplay());

        var child = mock(CurrentContext.class);
        var first = mock(ExtensionOperation.class);
        var second = mock(ExtensionOperation.class);
        when(child.reserve("map-iteration-0")).thenReturn(first);
        when(child.reserve("map-iteration-1")).thenReturn(second);
        when(first.runInChildContextAsync(
                        eq(MAP_ITERATION.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(new CompletedFuture<>("a0"));
        when(second.runInChildContextAsync(
                        eq(MAP_ITERATION.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(new CompletedFuture<>("b1"));

        try (var ignoredContext = BaseContextImpl.attachCurrentContext(child);
                var ignoredReplay = ExtensionContextReplayContext.attach(false, null)) {
            var result = function.getValue().apply().result();
            assertEquals(List.of("a0", "b1"), result.results());
        }

        var iterationConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(first)
                .runInChildContextAsync(
                        eq(MAP_ITERATION.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        iterationConfig.capture());
        assertTrue(iterationConfig.getValue().isVirtual());
        assertSame(serDes, iterationConfig.getValue().serDes());
        var failedIteration = Operation.builder()
                .id("iteration-id")
                .name("map-iteration-0")
                .type(OperationType.CONTEXT)
                .subType(MAP_ITERATION.getValue())
                .status(OperationStatus.FAILED)
                .build();
        var translated = iterationConfig
                .getValue()
                .errorHandler()
                .translate(new ExtensionContextFailure(failedIteration, null, List.of()));
        var failure = assertInstanceOf(MapIterationFailedException.class, translated);
        assertSame(failedIteration, failure.getOperation());
    }

    @Test
    void validateCompletedReplayReservesSkippedIterationsBeforeCompletedIterations() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var config = DurableMapOperation.MapConfig.builder()
                .serDes(new JacksonSerDes())
                .build();
        when(context.reserve("map")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(MAP.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(mockMapFuture());

        DurableMapOperation.mapAsync(
                context,
                "map",
                List.of("skipped", "completed"),
                TypeToken.get(String.class),
                (item, index, child) -> item,
                config);

        var function = extensionFunction();
        verify(parent).runInChildContextAsync(eq(MAP.getValue()), any(TypeToken.class), function.capture(), any());
        var child = mock(CurrentContext.class);
        var skipped = mock(ExtensionOperation.class);
        var completed = mock(ExtensionOperation.class);
        when(child.reserve("map-iteration-0")).thenReturn(skipped);
        when(child.reserve("map-iteration-1")).thenReturn(completed);
        when(completed.runInChildContextAsync(
                        eq(MAP_ITERATION.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(new CompletedFuture<>("completed"));
        var replayState = new MapResult<>(
                List.of(MapResult.MapResultItem.<String>skipped(), MapResult.MapResultItem.succeeded("completed")),
                MIN_SUCCESSFUL_REACHED);

        MapResult<String> result;
        try (var ignoredContext = BaseContextImpl.attachCurrentContext(child);
                var ignoredReplay = ExtensionContextReplayContext.attach(false, true, replayState)) {
            result = function.getValue().apply().result();
        }

        assertEquals(replayState, result);
        var reservations = inOrder(child);
        reservations.verify(child).reserve("map-iteration-0");
        reservations.verify(child).reserve("map-iteration-1");
        verify(skipped, never())
                .runInChildContextAsync(
                        any(String.class),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class));
    }

    @Test
    void replayingCompletedCustomNamedMapRejectsRemovedItem() {
        assertCompletedReplayCardinalityMismatch(List.of("first"), 2);
    }

    @Test
    void replayingCompletedCustomNamedMapRejectsAddedItem() {
        assertCompletedReplayCardinalityMismatch(List.of("first", "second", "third"), 2);
    }

    private void assertCompletedReplayCardinalityMismatch(List<String> items, int replayItemCount) {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var config = DurableMapOperation.MapConfig.builder()
                .serDes(new JacksonSerDes())
                .itemNamer(String.class, (item, index) -> item)
                .build();
        when(context.reserve("map")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(MAP.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(mockMapFuture());

        DurableMapOperation.mapAsync(
                context, "map", items, TypeToken.get(String.class), (item, index, child) -> item, config);

        var function = extensionFunction();
        verify(parent).runInChildContextAsync(eq(MAP.getValue()), any(TypeToken.class), function.capture(), any());
        var replayItems = IntStream.range(0, replayItemCount)
                .mapToObj(index -> MapResult.MapResultItem.succeeded("item-" + index))
                .toList();
        var replayState = new MapResult<>(replayItems, MIN_SUCCESSFUL_REACHED);

        try (var ignoredContext = BaseContextImpl.attachCurrentContext(mock(CurrentContext.class));
                var ignoredReplay = ExtensionContextReplayContext.attach(true, replayState)) {
            var exception = assertThrows(NonDeterministicExecutionException.class, () -> function.getValue()
                    .apply());
            assertTrue(exception.getMessage().contains("Expected " + replayItemCount + ", got " + items.size()));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionContextFunction<MapResult<String>>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionContextFunction.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<MapResult<String>> mockMapFuture() {
        return mock(DurableFuture.class);
    }

    private interface CurrentContext extends DurableContext, ExtensionContext {}

    private record CompletedFuture<T>(T result) implements DurableFuture<T> {
        @Override
        public T get() {
            return result;
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
