// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ParallelExampleTest {

    @Test
    void testParallelExampleRunsSuccessfully() {
        var handler = new ParallelExample();
        var runner = LocalDurableTestRunner.create(ParallelExample.Input.class, handler);

        var input = new ParallelExample.Input(List.of("apple", "banana", "cherry"));
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelExample.Output.class);
        assertEquals(3, output.totalProcessed());
        assertTrue(output.results().contains("APPLE"));
        assertTrue(output.results().contains("BANANA"));
        assertTrue(output.results().contains("CHERRY"));
    }

    @Test
    void testParallelExampleWithSingleItem() {
        var handler = new ParallelExample();
        var runner = LocalDurableTestRunner.create(ParallelExample.Input.class, handler);

        var input = new ParallelExample.Input(List.of("hello"));
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelExample.Output.class);
        assertEquals(1, output.totalProcessed());
        assertEquals(List.of("HELLO"), output.results());
    }

    @Test
    void testParallelExampleWithEmptyInput() {
        var handler = new ParallelExample();
        var runner = LocalDurableTestRunner.create(ParallelExample.Input.class, handler);

        var input = new ParallelExample.Input(List.of());
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelExample.Output.class);
        assertEquals(0, output.totalProcessed());
        assertTrue(output.results().isEmpty());
    }
}
