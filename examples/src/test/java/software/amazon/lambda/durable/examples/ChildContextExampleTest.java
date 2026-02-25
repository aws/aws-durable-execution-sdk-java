// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ChildContextExampleTest {

    @Test
    void testChildContextExampleRunsToCompletion() {
        var handler = new ChildContextExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Alice");
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(
                "Order for Alice [validated] | Stock available for Alice [confirmed] | Base rate for Alice + regional adjustment [shipping ready]",
                result.getResult(String.class));
    }

    @Test
    void testChildContextExampleSuspendsOnFirstRun() {
        var handler = new ChildContextExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Bob");

        // First run should suspend due to wait operations inside child contexts
        var result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }

    @Test
    void testChildContextExampleReplay() {
        var handler = new ChildContextExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Alice");

        // First full execution
        var result1 = runner.runUntilComplete(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());

        // Replay — should return cached results
        var result2 = runner.run(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(result1.getResult(String.class), result2.getResult(String.class));
    }
}
