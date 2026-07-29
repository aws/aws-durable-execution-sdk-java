// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;

/** Integration tests for MapOperation with itemNamer functionality. */
class MapOperationItemNamerTest {

    @Test
    void mapOperation_withItemNamer_constructsSuccessfully() {
        // Setup minimal mock context with execution manager
        DurableContextImpl mockContext = mock(DurableContextImpl.class);
        ExecutionManager mockExecutionManager = mock(ExecutionManager.class);
        when(mockContext.getExecutionManager()).thenReturn(mockExecutionManager);
        
        // Create custom item namer
        BiFunction<Object, Integer, String> itemNamer = (item, idx) -> "process-" + item + "-" + idx;

        MapConfig config = MapConfig.builder()
                .itemNamer(itemNamer)
                .maxConcurrency(2)
                .build();

        List<String> items = List.of("order-101", "order-102", "order-103");

        // Create MapOperation - should construct without errors
        MapOperation<String, String> operation = new MapOperation<>(
                OperationIdentifier.of("map-1", "process_orders", OperationSubType.MAP),
                items,
                (item, index, ctx) -> "processed-" + item,
                TypeToken.get(String.class),
                config,
                mockContext
        );

        // Verify the operation was created
        assertNotNull(operation);
        // Config should have the item namer
        assertSame(itemNamer, config.itemNamer());
    }

    @Test
    void mapOperation_withoutItemNamer_constructsSuccessfully() {
        // Default config (no itemNamer)
        MapConfig config = MapConfig.builder().build();

        // Should not throw NPE
        assertNull(config.itemNamer());

        // Operation should be constructible
        DurableContextImpl mockContext = mock(DurableContextImpl.class);
        ExecutionManager mockExecutionManager = mock(ExecutionManager.class);
        when(mockContext.getExecutionManager()).thenReturn(mockExecutionManager);

        MapOperation<String, String> operation = new MapOperation<>(
                OperationIdentifier.of("map-1", "test", OperationSubType.MAP),
                List.of("a", "b"),
                (item, index, ctx) -> item,
                TypeToken.get(String.class),
                config,
                mockContext
        );

        assertNotNull(operation);
    }

    @Test
    void mapOperation_withNullItemNamer_constructsSuccessfully() {
        // Explicitly null itemNamer
        MapConfig config = MapConfig.builder()
                .itemNamer(null)
                .build();

        assertNull(config.itemNamer());

        DurableContextImpl mockContext = mock(DurableContextImpl.class);
        ExecutionManager mockExecutionManager = mock(ExecutionManager.class);
        when(mockContext.getExecutionManager()).thenReturn(mockExecutionManager);

        MapOperation<String, String> operation = new MapOperation<>(
                OperationIdentifier.of("map-1", "test", OperationSubType.MAP),
                List.of("x", "y"),
                (item, index, ctx) -> item,
                TypeToken.get(String.class),
                config,
                mockContext
        );

        assertNotNull(operation);
    }
}