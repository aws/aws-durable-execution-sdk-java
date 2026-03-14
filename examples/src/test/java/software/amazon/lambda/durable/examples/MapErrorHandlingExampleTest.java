// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MapErrorHandlingExampleTest {

    @Test
    void testPartialFailure() {
        var handler = new MapErrorHandlingExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        var output = result.getResult(String.class);

        // 3 of 5 orders succeed, 2 fail
        assertTrue(output.contains("succeeded=3"));
        assertTrue(output.contains("failed=2"));

        // Successful orders are in the results
        assertTrue(output.contains("Processed order-1 for Alice"));
        assertTrue(output.contains("Processed order-3 for Alice"));
        assertTrue(output.contains("Processed order-5 for Alice"));

        // Error messages are captured
        assertTrue(output.contains("Invalid order: order-INVALID"));
        assertTrue(output.contains("Processing error for: order-ERROR"));
    }

    @Test
    void testReplay() {
        var handler = new MapErrorHandlingExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Bob");
        var result1 = runner.runUntilComplete(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());

        // Replay — errors are not preserved in checkpoints (Throwable is not serializable),
        // so the replay result will show failed=0 instead of failed=2.
        // The successful results should still match.
        var result2 = runner.runUntilComplete(input);
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        var output = result2.getResult(String.class);
        assertTrue(output.contains("succeeded=3"));
        assertTrue(output.contains("Processed order-1 for Bob"));
        assertTrue(output.contains("Processed order-3 for Bob"));
        assertTrue(output.contains("Processed order-5 for Bob"));
    }
}
