// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;

class NestedStepExampleTest {

    @Test
    void testNestedStepExample() {
        // Test nested step calling with stepAsync
        var handler = new NestedStepExample();
        var runner = LocalDurableTestRunner.create(Object.class, handler);

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("async-result-processed", result.getResult(String.class));
    }

    @Test
    void testReplay() {
        // Test replay behavior with nested steps
        var handler = new NestedStepExample();
        var runner = LocalDurableTestRunner.create(Object.class, handler);

        var input = "replay-test";

        // First execution
        var result1 = runner.run(input);

        // Second execution (replay)
        var result2 = runner.run(input);

        assertEquals(result1.getStatus(), result2.getStatus());
        assertEquals(result1.getResult(String.class), result2.getResult(String.class));
    }
}
