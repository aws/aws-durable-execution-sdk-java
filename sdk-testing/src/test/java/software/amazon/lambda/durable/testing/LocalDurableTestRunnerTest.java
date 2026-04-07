// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.TypeToken.get;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ExecutionStatus;

class LocalDurableTestRunnerTest {

    @Test
    void testSimpleExecution() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var result = ctx.step("process", String.class, stepCtx -> "Hello, " + input);
            return result;
        });

        var testResult = runner.run("World");

        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals("Hello, World", testResult.getResult(String.class));
    }

    @Test
    void testMultipleSteps() {
        var runner = LocalDurableTestRunner.create(Integer.class, (input, ctx) -> {
                    var step1 = ctx.step("add", Integer.class, stepCtx -> input + 10);
                    var step2 = ctx.step("multiply", Integer.class, stepCtx -> step1 * 2);
                    var step3 = ctx.step("subtract", Integer.class, stepCtx -> step2 - 5);
                    return step3;
                })
                .withOutputType(Integer.class);

        var testResult = runner.run(5);

        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals(25, testResult.getResult()); // (5 + 10) * 2 - 5 = 25
    }

    @Test
    void testGetOperation() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            ctx.step("step-1", String.class, stepCtx -> "result1");
            ctx.step("step-2", String.class, stepCtx -> "result2");
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
        assertEquals("result2", op2.getStepResult(get(String.class)));
    }

    @Test
    void testGenericTypeInput() {
        var resultType = new TypeToken<ArrayList<String>>() {};
        var runner = LocalDurableTestRunner.create(resultType, (input, ctx) -> {
            return ctx.step("process", resultType, stepCtx -> {
                var reversed = new ArrayList<>(input);
                Collections.reverse(reversed);
                return reversed;
            });
        });

        var testResult = runner.run(new ArrayList<>(List.of("item1", "item2")));

        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals(List.of("item2", "item1"), testResult.getResult(resultType));
    }
}
