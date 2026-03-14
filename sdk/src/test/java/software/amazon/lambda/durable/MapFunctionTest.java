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
        MapFunction<String, String> fn = (ctx, item, index) -> item.toUpperCase();

        var result = fn.apply(null, "hello", 0);

        assertEquals("HELLO", result);
    }

    @Test
    void receivesCorrectIndex() throws Exception {
        MapFunction<String, Integer> fn = (ctx, item, index) -> index;

        assertEquals(0, fn.apply(null, "a", 0));
        assertEquals(5, fn.apply(null, "b", 5));
    }

    @Test
    void canThrowCheckedException() {
        MapFunction<String, String> fn = (ctx, item, index) -> {
            throw new Exception("checked");
        };

        var ex = assertThrows(Exception.class, () -> fn.apply(null, "x", 0));
        assertEquals("checked", ex.getMessage());
    }

    @Test
    void canThrowRuntimeException() {
        MapFunction<String, String> fn = (ctx, item, index) -> {
            throw new IllegalArgumentException("bad input");
        };

        var ex = assertThrows(IllegalArgumentException.class, () -> fn.apply(null, "x", 0));
        assertEquals("bad input", ex.getMessage());
    }
}
