// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;

class LocalDurableTestRunnerTest {

    @Test
    void testSimpleExecution() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var result = ctx.step("process", String.class, () -> "Hello, " + input);
            return result;
        });

        var testResult = runner.run("World");

        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals("Hello, World", testResult.getResult(String.class));
    }

    @Test
    void testMultipleSteps() {
        var runner = LocalDurableTestRunner.create(Integer.class, (input, ctx) -> {
            var step1 = ctx.step("add", Integer.class, () -> input + 10);
            var step2 = ctx.step("multiply", Integer.class, () -> step1 * 2);
            var step3 = ctx.step("subtract", Integer.class, () -> step2 - 5);
            return step3;
        });

        var testResult = runner.run(5);

        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals(25, testResult.getResult(Integer.class)); // (5 + 10) * 2 - 5 = 25
    }

    @Test
    void testGetOperation() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            ctx.step("step-1", String.class, () -> "result1");
            ctx.step("step-2", String.class, () -> "result2");
            return "done";
        });

        runner.run("test");

        var op1 = runner.getOperation("step-1");
        assertNotNull(op1);
        assertEquals("step-1", op1.getName());
        assertEquals("result1", op1.getStepResult(String.class));

        var op2 = runner.getOperation("step-2");
        assertNotNull(op2);
        assertEquals("step-2", op2.getName());
        assertEquals("result2", op2.getStepResult(String.class));
    }
}
