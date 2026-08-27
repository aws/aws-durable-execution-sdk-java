// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.DurableMapOperation;
import software.amazon.lambda.durable.operation.DurableMapOperation.MapItemContext;

class DurableMapOperationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void mapExposesItemIndexThroughScopedContext() {
        var context = mock(CurrentContext.class);
        var parent = mock(ExtensionOperation.class);
        var iteration = mock(ExtensionOperation.class);
        var expected = new MapResult<>(
                List.of(MapResult.MapResultItem.succeeded("VALUE")),
                software.amazon.lambda.durable.model.ConcurrencyCompletionStatus.ALL_COMPLETED);
        var parentFuture = CompletableFuture.completedFuture(expected);
        when(context.getDurableConfig()).thenReturn(DurableConfig.builder().build());
        when(context.reserve("map")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(OperationSubType.MAP.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(parentFuture);
        BaseContextImpl.setCurrentContext(context);

        var actual = DurableMapOperation.map("map", List.of("value"), String.class, item -> {
            assertEquals(0, MapItemContext.getCurrentContext().getIndex());
            return item.toUpperCase();
        });

        assertSame(expected, actual);
        @SuppressWarnings("unchecked")
        var parentFunction = (ArgumentCaptor<ExtensionContextFunction<MapResult<String>>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(ExtensionContextFunction.class);
        verify(parent)
                .runInChildContextAsync(
                        eq(OperationSubType.MAP.getValue()),
                        any(TypeToken.class),
                        parentFunction.capture(),
                        any(ExtensionContextConfig.class));

        when(context.reserve("map-iteration-0")).thenReturn(iteration);
        when(iteration.runInChildContextAsync(
                        eq(OperationSubType.MAP_ITERATION.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(CompletableFuture.completedFuture("VALUE"));
        try (var ignoredContext = BaseContextImpl.attachCurrentContext(context);
                var ignoredReplay = ExtensionContextReplayContext.attach(false, null)) {
            parentFunction.getValue().apply().toCompletableFuture().join();
        }

        @SuppressWarnings("unchecked")
        var itemFunction = (ArgumentCaptor<ExtensionContextFunction<String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(ExtensionContextFunction.class);
        verify(iteration)
                .runInChildContextAsync(
                        eq(OperationSubType.MAP_ITERATION.getValue()),
                        eq(TypeToken.get(String.class)),
                        itemFunction.capture(),
                        any(ExtensionContextConfig.class));
        try (var ignored = BaseContextImpl.attachCurrentContext(context)) {
            assertEquals(
                    "VALUE",
                    itemFunction.getValue().apply().toCompletableFuture().join().result());
        }
        assertThrows(IllegalStateException.class, MapItemContext::getCurrentContext);
    }

    private interface CurrentContext extends DurableContext, ExtensionContext {}
}
