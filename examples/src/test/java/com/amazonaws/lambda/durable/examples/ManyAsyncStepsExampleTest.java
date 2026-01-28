// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;

class ManyAsyncStepsExampleTest {

    @Test
    void testManyAsyncSteps() {
        var handler = new ManyAsyncStepsExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncStepsExample.Input.class, handler);

        var input = new ManyAsyncStepsExample.Input(2);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(String.class);
        assertNotNull(output);
        assertTrue(output.contains("500 async steps"));

        // Sum of 0..499 * 2 = 499 * 500 / 2 * 2 = 249500
        assertTrue(output.contains("249500"));
    }

    @Test
    void testManyAsyncStepsWithDefaultMultiplier() {
        var handler = new ManyAsyncStepsExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncStepsExample.Input.class, handler);

        var input = new ManyAsyncStepsExample.Input(1);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Sum of 0..499 = 499 * 500 / 2 = 124750
        assertTrue(result.getResult(String.class).contains("124750"));
    }

    @Test
    void testOperationsAreTracked() {
        var handler = new ManyAsyncStepsExample();
        var runner = LocalDurableTestRunner.create(ManyAsyncStepsExample.Input.class, handler);

        var result = runner.runUntilComplete(new ManyAsyncStepsExample.Input(1));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Verify some operations are tracked
        assertNotNull(result.getOperation("compute-0"));
        assertNotNull(result.getOperation("compute-499"));
        assertNotNull(result.getOperation("compute-250"));
    }
}
