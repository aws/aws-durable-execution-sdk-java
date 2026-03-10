// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ManyAsyncChildContextExampleTest {

    @Test
    void testManyAsyncSteps() {
        var handler = new ManyAsyncChildContextExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncChildContextExample.Input.class, handler);

        var input = new ManyAsyncChildContextExample.Input(2);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(String.class);
        assertNotNull(output);
        assertTrue(output.contains("500 async child context"));

        // Sum of 0..499 * 2 = 499 * 500 / 2 * 2 = 249500
        assertTrue(output.contains("249500"));
    }

    @Test
    void testManyAsyncStepsWithDefaultMultiplier() {
        var handler = new ManyAsyncChildContextExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncChildContextExample.Input.class, handler);

        var input = new ManyAsyncChildContextExample.Input(1);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Sum of 0..499 = 499 * 500 / 2 = 124750
        assertTrue(result.getResult(String.class).contains("124750"));
    }

    @Test
    void testOperationsAreTracked() {
        var handler = new ManyAsyncChildContextExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncChildContextExample.Input.class, handler);

        var result = runner.runUntilComplete(new ManyAsyncChildContextExample.Input(1));

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
