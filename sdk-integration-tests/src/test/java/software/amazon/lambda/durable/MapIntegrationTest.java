// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MapIntegrationTest {

    @Test
    void testSimpleMap() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            var result = context.map("process-items", items, String.class, (ctx, item, index) -> {
                return item.toUpperCase();
            });

            assertTrue(result.allSucceeded());
            assertEquals(3, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));
            assertEquals("C", result.getResult(2));

            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B,C", result.getResult(String.class));
    }

    @Test
    void testMapWithStepsInsideBranches() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("hello", "world");
            var result = context.map("map-with-steps", items, String.class, (ctx, item, index) -> {
                return ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
            });

            assertTrue(result.allSucceeded());
            return String.join(" ", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO WORLD", result.getResult(String.class));
    }
}
