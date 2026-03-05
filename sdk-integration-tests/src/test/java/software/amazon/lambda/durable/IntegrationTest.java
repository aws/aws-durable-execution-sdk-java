// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/** Some example test to test end to end behavior * */
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
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var result = context.step("process", String.class, () -> "Processed: " + input.value);
            return new TestOutput(result);
        });

        var result = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("Processed: test", result.getResult(TestOutput.class).result);
        assertEquals(1, result.getSucceededOperations().size());
    }

    @Test
    void testActualAsyncExecution() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var future = context.stepAsync("async-process", String.class, () -> "Async: " + input.value);
            try {
                var result = future.get();
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
    void testWaitSuspension() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var step1 = context.step("step1", String.class, () -> "Step 1 done");

            // This should throw SuspendExecutionException
            context.wait(null, Duration.ofMinutes(5));

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
    void testFullWaitOperation() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var step1 = context.step("step1", String.class, () -> "Step 1 done");

            // This should throw SuspendExecutionException
            context.wait(null, Duration.ofMinutes(5));

            // This should never execute in first run
            var step2 = context.step("step2", String.class, () -> "Step 2 done");
            return new TestOutput(step1 + " + " + step2);
        });

        var result = runner.runUntilComplete(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(3, result.getSucceededOperations().size());
        assertEquals("Step 1 done", result.getOperation("step1").getStepResult(String.class));
        assertEquals(OperationType.WAIT, result.getSucceededOperations().get(1).getType());
        assertEquals(
                OperationStatus.SUCCEEDED,
                result.getSucceededOperations().get(1).getStatus());
        assertEquals("Step 2 done", result.getOperation("step2").getStepResult(String.class));
        assertEquals("Step 1 done + Step 2 done", result.getResult(TestOutput.class).result);
    }

    @Test
    void testBasicReplay() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var result = context.step("process", String.class, () -> {
                return "Execution #" + executionCount.incrementAndGet() + ": " + input.value;
            });
            return new TestOutput(result);
        });

        // First execution
        var output1 = runner.run(new TestInput("replay-test"));
        assertEquals(ExecutionStatus.SUCCEEDED, output1.getStatus());

        // Second execution - should replay - will get state from previous run
        var output2 = runner.run(new TestInput("replay-test"));
        assertEquals(ExecutionStatus.SUCCEEDED, output2.getStatus());

        // Handler executed twice, but step only executed once
        assertEquals(1, executionCount.get());
        assertEquals("Execution #1: replay-test", output2.getResult(TestOutput.class).result);
        assertEquals(1, output2.getSucceededOperations().size());
    }

    @Test
    void testMultiStepWorkflowWithOperationInspection() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var step1 = context.step("validate", String.class, () -> "validated");
            var step2 = context.step("process", String.class, () -> step1 + "-processed");
            return new TestOutput(step2);
        });

        var result = runner.runUntilComplete(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, result.getOperations().size());
        assertEquals("validated", runner.getOperation("validate").getStepResult(String.class));
        assertEquals("validated-processed", runner.getOperation("process").getStepResult(String.class));
        assertEquals("validated-processed", result.getResult(TestOutput.class).result);
    }

    @Test
    void testOperationFiltering() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            context.step("good-step", String.class, () -> "ok");
            return "done";
        });

        var result = runner.runUntilComplete(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, result.getSucceededOperations().size());
        assertEquals(0, result.getFailedOperations().size());
        assertEquals("good-step", result.getSucceededOperations().get(0).getName());
    }

    @Test
    void testWaitOperationWithManualAdvance() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            context.step("good-step", String.class, () -> "ok");
            context.wait(null, Duration.ofSeconds(5));
            return "done";
        });

        var result = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.PENDING, result.getStatus());
        assertEquals(1, result.getSucceededOperations().size());
        assertEquals("good-step", result.getSucceededOperations().get(0).getName());

        runner.advanceTime();

        var result2 = runner.runUntilComplete(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(2, result2.getSucceededOperations().size());
    }

    @Test
    void testWaitAsyncReturnsNonBlockingFuture() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var step1 = context.step("step1", String.class, () -> "Step 1 done");

            // waitAsync should return immediately without blocking
            var waitFuture = context.waitAsync("async-wait", Duration.ofMinutes(5));

            // This step should execute before the wait completes
            var step2 = context.step("step2", String.class, () -> "Step 2 done");

            // Now block on the wait
            waitFuture.get();

            return new TestOutput(step1 + " + " + step2);
        });

        // First run should suspend at waitFuture.get() since the wait hasn't elapsed
        var result = runner.run(new TestInput("test"));
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance time so the wait completes, then re-run to finish
        runner.advanceTime();

        var result2 = runner.runUntilComplete(new TestInput("test"));
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("Step 1 done + Step 2 done", result2.getResult(TestOutput.class).result);
    }

    @Test
    void testWaitAsyncSuspendsOnGet() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var waitFuture = context.waitAsync(null, Duration.ofMinutes(5));

            // Calling get() should suspend execution
            waitFuture.get();

            var step = context.step("after-wait", String.class, () -> "done");
            return new TestOutput(step);
        });

        // First run should suspend at waitFuture.get()
        var result = runner.run(new TestInput("test"));
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance time so the wait completes, then re-run to finish
        runner.advanceTime();

        var result2 = runner.runUntilComplete(new TestInput("test"));
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("done", result2.getResult(TestOutput.class).result);
    }

    @Test
    void testWaitAsyncWithoutName() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var waitFuture = context.waitAsync(null, Duration.ofSeconds(10));
            waitFuture.get();
            return new TestOutput("done");
        });

        var result = runner.runUntilComplete(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("done", result.getResult(TestOutput.class).result);
    }
}
