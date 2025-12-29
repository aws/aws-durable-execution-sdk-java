// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntegrationTest {

    static class TestInput {
        public String value;

        public TestInput() {}

        public TestInput(String value) {
            this.value = value;
        }
    }

    static class TestOutput {
        public String result;

        public TestOutput() {}

        public TestOutput(String result) {
            this.result = result;
        }
    }

    @Test
    void testActualSyncExecution() {
        var runner = new LocalDurableTestRunner<>(TestInput.class, (input, context) -> {
            // This should actually execute
            var result = context.step("process", String.class, () -> "Processed: " + input.value);
            return new TestOutput(result);
        });

        var result = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("Processed: test", result.getResult(TestOutput.class).result);
    }

    @Test
    void testActualAsyncExecution() {
        var runner = new LocalDurableTestRunner<>(TestInput.class, (input, context) -> {
            // This should actually execute async
            var future = context.stepAsync("async-process", String.class, () -> "Async: " + input.value);
            try {
                var result = future.get(); // This should block and wait
                return new TestOutput(result);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        var result = runner.run(new TestInput("async-test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("Async: async-test", result.getResult(TestOutput.class).result);
    }

    @Test
    void testActualWaitSuspension() {
        var runner = new LocalDurableTestRunner<>(TestInput.class, (input, context) -> {
            var step1 = context.step("step1", String.class, () -> "Step 1 done");

            // This should throw SuspendExecutionException
            context.wait(Duration.ofMinutes(5));

            // This should never execute in first run
            var step2 = context.step("step2", String.class, () -> "Step 2 done");
            return new TestOutput(step1 + " + " + step2);
        });

        var result = runner.run(new TestInput("wait-test"));

        // Should be PENDING because wait suspended execution
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // For PENDING status, getResult should throw
        assertThrows(IllegalStateException.class, () -> {
            result.getResult(TestOutput.class);
        });
    }

    @Test
    void testActualReplay() {
        var executionCount = new AtomicInteger(0);

        var runner = new LocalDurableTestRunner<>(TestInput.class, (input, context) -> {
            var result = context.step("process", String.class, () -> {
                return "Execution #" + executionCount.incrementAndGet() + ": " + input.value;
            });
            return new TestOutput(result);
        });

        // First execution
        var output1 = runner.run(new TestInput("replay-test"));
        assertEquals(ExecutionStatus.SUCCEEDED, output1.getStatus());

        // Second execution - should replay
        var output2 = runner.run(new TestInput("replay-test"));
        if (output2.getStatus() == ExecutionStatus.FAILED) {
            System.out.println(
                    "Second execution failed with error: " + output2.getOutput().error());
        }
        assertEquals(ExecutionStatus.SUCCEEDED, output2.getStatus());

        // Handler executed twice, but step only executed once (replayed from cache)
        assertEquals(1, executionCount.get()); // Step only executed once (replay worked!)
        assertEquals(
                output1.getResult(TestOutput.class).result,
                output2.getResult(TestOutput.class).result); // Same result (cached)
    }
}
