// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ComplexMapExampleTest {

    @Test
    void testComplexMapExample() {
        var handler = new ComplexMapExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        var output = result.getResult(String.class);

        // Part 1: all 3 orders processed with step + wait + step
        assertTrue(output.contains("done:validated:order-1:Alice"));
        assertTrue(output.contains("done:validated:order-2:Alice"));
        assertTrue(output.contains("done:validated:order-3:Alice"));

        // Part 2: early termination after 2 healthy servers
        assertTrue(output.contains("reason=MIN_SUCCESSFUL_REACHED"));
        assertTrue(output.contains("healthy"));
    }

    @Test
    void testReplay() {
        var handler = new ComplexMapExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Bob");
        var result1 = runner.runUntilComplete(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());

        // Replay — should use cached results.
        // Structural assertion because the first map has wait() inside branches with unlimited
        // concurrency, which can cause non-deterministic thread scheduling across invocations.
        var result2 = runner.runUntilComplete(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        var output = result2.getResult(String.class);
        assertTrue(output.contains("done:validated:order-1:Bob"));
        assertTrue(output.contains("done:validated:order-2:Bob"));
        assertTrue(output.contains("done:validated:order-3:Bob"));
        assertTrue(output.contains("reason=MIN_SUCCESSFUL_REACHED"));
        assertTrue(output.contains("healthy"));
    }
}
