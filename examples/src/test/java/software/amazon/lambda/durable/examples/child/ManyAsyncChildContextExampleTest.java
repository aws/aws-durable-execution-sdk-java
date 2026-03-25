// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.step.ManyAsyncStepsExample;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ManyAsyncChildContextExampleTest {

    @Test
    void testManyAsyncSteps() {
        var handler = new ManyAsyncChildContextExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncChildContextExample.Input.class, handler);

        var input = new ManyAsyncChildContextExample.Input(2, 500);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ManyAsyncStepsExample.Output.class);
        assertNotNull(output);

        // Sum of 0..499 * 2 = 499 * 500 / 2 * 2 = 249500
        assertEquals(249500, output.result());
    }

    @Test
    void testManyAsyncStepsWithDefaultMultiplier() {
        var handler = new ManyAsyncChildContextExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncChildContextExample.Input.class, handler);

        var input = new ManyAsyncChildContextExample.Input(1, 500);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Sum of 0..499 = 499 * 500 / 2 = 124750
        assertEquals(
                124750,
                result.getResult(ManyAsyncChildContextExample.Output.class).result());
    }

    @Test
    void testOperationsAreTracked() {
        var handler = new ManyAsyncChildContextExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncChildContextExample.Input.class, handler);

        var result = runner.runUntilComplete(new ManyAsyncChildContextExample.Input(1, 500));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Verify some operations are tracked
        assertNotNull(result.getOperation("compute-0"));
        assertNotNull(result.getOperation("compute-499"));
        assertNotNull(result.getOperation("compute-250"));

        assertNotNull(result.getOperation("child-0"));
        assertNotNull(result.getOperation("child-499"));
        assertNotNull(result.getOperation("child-250"));
    }
}
