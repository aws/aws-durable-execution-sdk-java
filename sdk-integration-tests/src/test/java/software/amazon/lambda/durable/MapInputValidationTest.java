// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MapInputValidationTest {

    @Test
    void mapWithNullCollection_throwsNullPointerException() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            context.<String, String>map("test", null, String.class, (item, index, ctx) -> item);
            return "done";
        });

        var result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void mapWithNullFunction_throwsNullPointerException() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            context.map("test", List.of("a"), String.class, null);
            return "done";
        });

        var result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void mapWithHashSet_throwsIllegalArgumentException() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = new HashSet<>(List.of("a", "b"));
            context.map("test", items, String.class, (item, index, ctx) -> item);
            return "done";
        });

        var result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void mapWithEmptyCollection_returnsEmptyMapResult() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map("empty-map", List.<String>of(), String.class, (item, index, ctx) -> item);

            assertEquals(0, result.size());
            assertTrue(result.allSucceeded());
            assertEquals(CompletionReason.ALL_COMPLETED, result.completionReason());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }
}
