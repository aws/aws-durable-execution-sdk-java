// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class MapConfigTest {

    @Test
    void defaultBuilder_hasNullMaxConcurrency() {
        var config = MapConfig.builder().build();

        assertEquals(Integer.MAX_VALUE, config.maxConcurrency());
    }

    @Test
    void defaultBuilder_completionConfigDefaultsToAllCompleted() {
        var config = MapConfig.builder().build();

        var completion = config.completionConfig();
        assertNotNull(completion);
        assertNull(completion.minSuccessful());
        assertNull(completion.toleratedFailureCount());
        assertNull(completion.toleratedFailurePercentage());
    }

    @Test
    void defaultBuilder_hasNullSerDes() {
        var config = MapConfig.builder().build();

        assertNull(config.serDes());
    }

    @Test
    void builderWithMaxConcurrency() {
        var config = MapConfig.builder().maxConcurrency(5).build();

        assertEquals(5, config.maxConcurrency());
    }

    @Test
    void builderWithCompletionConfig() {
        var completion = CompletionConfig.allSuccessful();

        var config = MapConfig.builder().completionConfig(completion).build();

        assertSame(completion, config.completionConfig());
    }

    @Test
    void builderWithSerDes() {
        var serDes = new JacksonSerDes();

        var config = MapConfig.builder().serDes(serDes).build();

        assertSame(serDes, config.serDes());
    }

    @Test
    void builderWithItemNamer() {
        BiFunction<Object, Integer, String> namer = (item, index) -> item + "-" + index;

        var config = MapConfig.builder().itemNamer(namer).build();

        assertSame(namer, config.itemNamer());
    }

    @Test
    void builderWithTypedItemNamer_receivesItemWithoutCast() {
        // The lambda parameter is the domain type, so no cast is needed to reach its members.
        var config = MapConfig.builder()
                .itemNamer(Order.class, (order, index) -> order.id() + "-" + index)
                .build();

        assertEquals("a1-0", config.itemNamer().apply(new Order("a1"), 0));
    }

    @Test
    void builderWithTypedItemNamer_acceptsAlreadyTypedFunction() {
        // A BiFunction<Order, ...> is rejected by itemNamer(BiFunction) but accepted here.
        BiFunction<Order, Integer, String> namer = (order, index) -> order.id();

        var config = MapConfig.builder().itemNamer(Order.class, namer).build();

        assertEquals("a1", config.itemNamer().apply(new Order("a1"), 0));
    }

    @Test
    void builderWithTypedItemNamer_preservesNullResult() {
        var config = MapConfig.builder()
                .itemNamer(Order.class, (order, index) -> null)
                .build();

        assertNull(config.itemNamer().apply(new Order("a1"), 0));
    }

    @Test
    void builderWithTypedItemNamer_nullNamerLeavesDefaultNaming() {
        var config = MapConfig.builder().itemNamer(Order.class, null).build();

        assertNull(config.itemNamer());
    }

    @Test
    void builderWithTypedItemNamer_nullItemTypeThrows() {
        var exception = assertThrows(
                NullPointerException.class, () -> MapConfig.builder().itemNamer(null, (order, index) -> "x"));

        assertEquals("itemType cannot be null", exception.getMessage());
    }

    @Test
    void builderWithTypedItemNamer_mismatchedItemTypeThrows() {
        var config = MapConfig.builder()
                .itemNamer(Order.class, (order, index) -> order.id())
                .build();

        assertThrows(ClassCastException.class, () -> config.itemNamer().apply("not-an-order", 0));
    }

    @Test
    void builderRejectsTypedItemNamerWithFlatNesting() {
        var builder =
                MapConfig.builder().nestingType(NestingType.FLAT).itemNamer(Order.class, (order, index) -> order.id());

        var exception = assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("itemNamer is not supported with FLAT map nesting", exception.getMessage());
    }

    @Test
    void builderRejectsItemNamerWithFlatNesting() {
        var builder = MapConfig.builder().nestingType(NestingType.FLAT).itemNamer((item, index) -> "item-" + index);

        var exception = assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("itemNamer is not supported with FLAT map nesting", exception.getMessage());
    }

    @Test
    void builderChaining() {
        var completion = CompletionConfig.firstSuccessful();
        var serDes = new JacksonSerDes();

        var config = MapConfig.builder()
                .maxConcurrency(3)
                .completionConfig(completion)
                .serDes(serDes)
                .build();

        assertEquals(3, config.maxConcurrency());
        assertSame(completion, config.completionConfig());
        assertSame(serDes, config.serDes());
    }

    @Test
    void toBuilder_preservesValues() {
        var completion = CompletionConfig.minSuccessful(2);
        var serDes = new JacksonSerDes();
        BiFunction<Object, Integer, String> namer = (item, index) -> "item-" + index;
        var original = MapConfig.builder()
                .maxConcurrency(4)
                .completionConfig(completion)
                .serDes(serDes)
                .itemNamer(namer)
                .build();

        var copy = original.toBuilder().build();

        assertEquals(4, copy.maxConcurrency());
        assertSame(completion, copy.completionConfig());
        assertSame(serDes, copy.serDes());
        assertSame(namer, copy.itemNamer());
    }

    @Test
    void toBuilder_canOverrideValues() {
        var original = MapConfig.builder().maxConcurrency(4).build();

        var modified = original.toBuilder().maxConcurrency(10).build();

        assertEquals(10, modified.maxConcurrency());
        assertEquals(4, original.maxConcurrency());
    }

    @Test
    void builderWithZeroMaxConcurrency_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> MapConfig.builder().maxConcurrency(0).build());
        assertEquals("maxConcurrency must be at least 1, got: 0", exception.getMessage());
    }

    @Test
    void builderWithNegativeMaxConcurrency_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> MapConfig.builder().maxConcurrency(-1).build());
        assertEquals("maxConcurrency must be at least 1, got: -1", exception.getMessage());
    }

    @Test
    void builderWithNullMaxConcurrency_shouldPass() {
        var config = MapConfig.builder().maxConcurrency(null).build();
        assertEquals(Integer.MAX_VALUE, config.maxConcurrency());
    }

    private record Order(String id) {}
}
