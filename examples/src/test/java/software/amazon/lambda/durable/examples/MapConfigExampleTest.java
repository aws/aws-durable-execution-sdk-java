// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MapConfigExampleTest {

    @Test
    void testSequentialAndEarlyTermination() {
        var handler = new MapConfigExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        var output = result.getResult(String.class);

        assertTrue(output.contains("ALPHA-Alice"));
        assertTrue(output.contains("BETA-Alice"));
        assertTrue(output.contains("GAMMA-Alice"));
        assertTrue(output.contains("reason=MIN_SUCCESSFUL_REACHED"));
        assertTrue(output.contains("server-1:healthy"));
        assertTrue(output.contains("server-2:healthy"));
    }

    @Test
    void testReplay() {
        var handler = new MapConfigExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Bob");
        var result1 = runner.runUntilComplete(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());

        // Replay — should use cached results
        var result2 = runner.runUntilComplete(input);
        assertEquals(result1.getResult(String.class), result2.getResult(String.class));
    }
}
