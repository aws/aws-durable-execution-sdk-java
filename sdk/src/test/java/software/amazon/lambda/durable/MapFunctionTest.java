// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MapFunctionTest {

    @Test
    void isFunctionalInterface() {
        assertTrue(MapFunction.class.isAnnotationPresent(FunctionalInterface.class));
    }

    @Test
    void canBeUsedAsLambda() throws Exception {
        MapFunction<String, String> fn = (item, index, ctx) -> item.toUpperCase();

        var result = fn.apply("hello", 0, null);

        assertEquals("HELLO", result);
    }

    @Test
    void receivesCorrectIndex() throws Exception {
        MapFunction<String, Integer> fn = (item, index, ctx) -> index;

        assertEquals(0, fn.apply("a", 0, null));
        assertEquals(5, fn.apply("b", 5, null));
    }

    @Test
    void canThrowCheckedException() {
        MapFunction<String, String> fn = (item, index, ctx) -> {
            throw new Exception("checked");
        };

        var ex = assertThrows(Exception.class, () -> fn.apply("x", 0, null));
        assertEquals("checked", ex.getMessage());
    }

    @Test
    void canThrowRuntimeException() {
        MapFunction<String, String> fn = (item, index, ctx) -> {
            throw new IllegalArgumentException("bad input");
        };

        var ex = assertThrows(IllegalArgumentException.class, () -> fn.apply("x", 0, null));
        assertEquals("bad input", ex.getMessage());
    }
}
