// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.vt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

@EnabledForJreRange(min = JRE.JAVA_21)
class ManyAsyncStepsVirtualThreadPoolExampleTest {

    @Test
    void testManyAsyncSteps() {
        var handler = new ManyAsyncStepsVirtualThreadPoolExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncStepsVirtualThreadPoolExample.Input.class, handler);

        var input = new ManyAsyncStepsVirtualThreadPoolExample.Input(2, 500);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ManyAsyncStepsVirtualThreadPoolExample.Output.class);
        assertNotNull(output);

        // Sum of 0..499 * 2 = 499 * 500 / 2 * 2 = 249500
        assertEquals(
                249500,
                result.getResult(ManyAsyncStepsVirtualThreadPoolExample.Output.class)
                        .result());
    }

    @Test
    void testManyAsyncStepsWithDefaultMultiplier() {
        var handler = new ManyAsyncStepsVirtualThreadPoolExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncStepsVirtualThreadPoolExample.Input.class, handler);

        var input = new ManyAsyncStepsVirtualThreadPoolExample.Input(1, 500);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Sum of 0..499 = 499 * 500 / 2 = 124750
        assertEquals(
                124750,
                result.getResult(ManyAsyncStepsVirtualThreadPoolExample.Output.class)
                        .result());
    }

    @Test
    void testOperationsAreTracked() {
        var handler = new ManyAsyncStepsVirtualThreadPoolExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncStepsVirtualThreadPoolExample.Input.class, handler);

        var result = runner.runUntilComplete(new ManyAsyncStepsVirtualThreadPoolExample.Input(1, 500));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Verify some operations are tracked
        assertNotNull(result.getOperation("compute-0"));
        assertNotNull(result.getOperation("compute-499"));
        assertNotNull(result.getOperation("compute-250"));
    }
}
