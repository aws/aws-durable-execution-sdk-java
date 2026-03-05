// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class WaitAsyncExampleTest {

    @Test
    void testWaitAsyncExampleCompletesSuccessfully() {
        var handler = new WaitAsyncExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("Processed: Alice", result.getResult(String.class));
    }

    @Test
    void testWaitAsyncExampleSuspendsOnFirstRun() {
        var handler = new WaitAsyncExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        // First run suspends because the wait hasn't elapsed yet
        var result = runner.run(new GreetingRequest("Bob"));
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance time so the wait completes, then re-run to finish
        runner.advanceTime();

        var result2 = runner.runUntilComplete(new GreetingRequest("Bob"));
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("Processed: Bob", result2.getResult(String.class));
    }
}
