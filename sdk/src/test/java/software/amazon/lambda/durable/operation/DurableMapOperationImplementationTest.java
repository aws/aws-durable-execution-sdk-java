// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.lambda.durable.model.OperationSubType.MAP;
import static software.amazon.lambda.durable.model.OperationSubType.MAP_ITERATION;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
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
        var config =
                MapConfig.builder().serDes(serDes).nestingType(NestingType.FLAT).build();
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
                config.toOperationConfig());

        assertSame(parentFuture, actual);
        var function = extensionFunction();
        var parentConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(parent)
                .runInChildContextAsync(
                        eq(MAP.getValue()), any(TypeToken.class), function.capture(), parentConfig.capture());
        assertSame(serDes, parentConfig.getValue().serDes());
        assertFalse(parentConfig.getValue().emitUserFunctionEvents());
        assertTrue(parentConfig.getValue().suppressLateChildCheckpoints());

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
