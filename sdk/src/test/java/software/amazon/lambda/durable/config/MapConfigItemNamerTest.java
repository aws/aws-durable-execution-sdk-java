// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** Tests for {@link MapConfig} itemNamer functionality. */
class MapConfigItemNamerTest {

    @Test
    void builder_withoutItemNamer_returnsNullItemNamer() {
        MapConfig config = MapConfig.builder().build();
        assertNull(config.itemNamer());
    }

    @Test
    void builder_withItemNamer_returnsConfiguredItemNamer() {
        BiFunction<Object, Integer, String> namer = (item, idx) -> "item-" + idx;
        MapConfig config = MapConfig.builder().itemNamer(namer).build();
        assertSame(namer, config.itemNamer());
    }

    @Test
    void itemNamer_calledWithItemAndIndex() {
        BiFunction<Object, Integer, String> namer = (item, idx) -> "name-" + item + "-" + idx;
        MapConfig config = MapConfig.builder().itemNamer(namer).build();

        String result = config.itemNamer().apply("test-item", 42);
        assertEquals("name-test-item-42", result);
    }

    @Test
    void toBuilder_preservesItemNamer() {
        BiFunction<Object, Integer, String> namer = (item, idx) -> "custom-" + idx;
        MapConfig original =
                MapConfig.builder().maxConcurrency(5).itemNamer(namer).build();

        MapConfig rebuilt = original.toBuilder().build();
        assertEquals(5, rebuilt.maxConcurrency());
        assertSame(namer, rebuilt.itemNamer());
    }

    @Test
    void toBuilder_canUpdateItemNamer() {
        BiFunction<Object, Integer, String> namer1 = (item, idx) -> "first-" + idx;
        BiFunction<Object, Integer, String> namer2 = (item, idx) -> "second-" + idx;

        MapConfig config = MapConfig.builder().itemNamer(namer1).build();

        MapConfig updated = config.toBuilder().itemNamer(namer2).build();

        assertSame(namer2, updated.itemNamer());
    }

    @Test
    void toBuilder_canRemoveItemNamer() {
        BiFunction<Object, Integer, String> namer = (item, idx) -> "name-" + idx;
        MapConfig config = MapConfig.builder().itemNamer(namer).build();

        MapConfig withoutNamer = config.toBuilder().itemNamer(null).build();

        assertNull(withoutNamer.itemNamer());
    }

    @Test
    void builder_withAllFields_includesItemNamer() {
        MapConfig config = MapConfig.builder()
                .maxConcurrency(3)
                .nestingType(NestingType.FLAT)
                .itemNamer((item, idx) -> "iter-" + idx)
                .build();

        assertEquals(3, config.maxConcurrency());
        assertEquals(NestingType.FLAT, config.nestingType());
        assertNotNull(config.itemNamer());
        assertEquals("iter-7", config.itemNamer().apply("anything", 7));
    }

    @Test
    void itemNamer_withDifferentItemTypes() {
        // String items
        BiFunction<Object, Integer, String> stringNamer = (item, idx) -> "str-" + item;
        MapConfig stringConfig = MapConfig.builder().itemNamer(stringNamer).build();
        assertEquals("str-hello", stringConfig.itemNamer().apply("hello", 0));

        // Integer items
        BiFunction<Object, Integer, String> intNamer = (item, idx) -> "num-" + item;
        MapConfig intConfig = MapConfig.builder().itemNamer(intNamer).build();
        assertEquals("num-123", intConfig.itemNamer().apply(123, 1));

        // Custom object items
        record User(String id, String name) {}
        BiFunction<Object, Integer, String> userNamer = (item, idx) -> {
            User user = (User) item;
            return "user-" + user.id();
        };
        MapConfig userConfig = MapConfig.builder().itemNamer(userNamer).build();
        assertEquals("user-u123", userConfig.itemNamer().apply(new User("u123", "Alice"), 2));
    }

    @Test
    void itemNamer_inheritsOtherConfigFields() {
        MapConfig base = MapConfig.builder()
                .maxConcurrency(10)
                .nestingType(NestingType.FLAT)
                .build();

        BiFunction<Object, Integer, String> namer = (item, idx) -> "named-" + idx;
        MapConfig withNamer = base.toBuilder().itemNamer(namer).build();

        assertEquals(10, withNamer.maxConcurrency());
        assertEquals(NestingType.FLAT, withNamer.nestingType());
        assertSame(namer, withNamer.itemNamer());
    }
}
