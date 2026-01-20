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
        var output = result.getResult(String.class);
        assertTrue(
                output.matches("slept-(5|6|7|8|9|10)-seconds"),
                "Expected format: slept-X-seconds where X is 5-10, got: " + output);
    }

    @Test
    void testReplay() {
        // Test replay behavior with nested steps
        var handler = new NestedStepExample();
        var runner = LocalDurableTestRunner.create(Object.class, handler);

        var input = "replay-test";

        // First execution
        var result1 = runner.run(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var output1 = result1.getResult(String.class);

        // Second execution (replay)
        var result2 = runner.run(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        var output2 = result2.getResult(String.class);

        assertEquals(output1, output2, "Replay must return identical result to prove idempotency");
    }
}
