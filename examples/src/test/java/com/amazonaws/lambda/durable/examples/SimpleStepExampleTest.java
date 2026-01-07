// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;

class SimpleStepExampleTest {

    @Test
    void testSimpleStepExample() {
        // Create handler
        var handler = new SimpleStepExample();

        // Create test runner
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        // Run with input
        var input = new GreetingRequest("Alice");
        var result = runner.run(input);

        // Verify result
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, ALICE!", result.getResult(String.class));
    }

    @Test
    void testWithDefaultName() {
        var handler = new SimpleStepExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest();
        var result = runner.run(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, WORLD!", result.getResult(String.class));
    }

    @Test
    void testReplay() {
        var handler = new SimpleStepExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        // First execution
        var input = new GreetingRequest("Bob");
        var result1 = runner.run(input);
        assertEquals("HELLO, BOB!", result1.getResult(String.class));

        // Second execution (replay) - should use cached results
        var result2 = runner.run(input);
        assertEquals("HELLO, BOB!", result2.getResult(String.class));
        assertEquals(result1.getResult(String.class), result2.getResult(String.class));
    }
}
