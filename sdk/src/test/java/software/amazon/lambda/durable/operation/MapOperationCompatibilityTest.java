// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.OperationIdentifier;

class MapOperationCompatibilityTest {

    @Test
    void retainsLegacyPublicConstructor() {
        assertDoesNotThrow(() -> MapOperation.class.getConstructor(
                OperationIdentifier.class,
                List.class,
                DurableContext.MapFunction.class,
                TypeToken.class,
                MapConfig.class,
                DurableContextImpl.class));
    }

    // The resolver below is the single source of iteration naming for both the legacy constructor and
    // DurableContextImpl.mapAsync, so these cases pin the contract that both paths share.

    @Test
    void resolveIterationNames_withoutNamer_usesDefaultNaming() {
        var names = MapOperation.resolveIterationNames(
                "orders", List.of("a", "b"), MapConfig.builder().build());

        assertEquals(List.of("orders-iteration-0", "orders-iteration-1"), names);
    }

    @Test
    void resolveIterationNames_withoutMapName_usesUnprefixedDefault() {
        var names = MapOperation.resolveIterationNames(
                null, List.of("a"), MapConfig.builder().build());

        assertEquals(List.of("map-iteration-0"), names);
    }

    @Test
    void resolveIterationNames_withNamer_usesCustomNames() {
        var config = MapConfig.builder()
                .itemNamer((item, index) -> item + "-" + index)
                .build();

        var names = MapOperation.resolveIterationNames("orders", List.of("a", "b"), config);

        assertEquals(List.of("a-0", "b-1"), names);
    }

    @Test
    void resolveIterationNames_preservesNullNamerResult() {
        var config = MapConfig.builder().itemNamer((item, index) -> null).build();

        var names = MapOperation.resolveIterationNames("orders", List.of("a"), config);

        assertEquals(1, names.size());
        assertNull(names.get(0));
    }

    @Test
    void resolveIterationNames_validatesCustomNames() {
        var config = MapConfig.builder().itemNamer((item, index) -> "").build();

        assertThrows(
                IllegalArgumentException.class,
                () -> MapOperation.resolveIterationNames("orders", List.of("a"), config));
    }

    @Test
    void resolveIterationNames_emptyItemsDoesNotInvokeNamer() {
        var calls = new AtomicInteger();
        var config = MapConfig.builder()
                .itemNamer((item, index) -> {
                    calls.incrementAndGet();
                    return "unused";
                })
                .build();

        var names = MapOperation.resolveIterationNames("orders", List.of(), config);

        assertEquals(List.of(), names);
        assertEquals(0, calls.get());
    }
}
