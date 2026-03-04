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

        var result = runner.run(new GreetingRequest("Bob"));

        // First run suspends because the wait hasn't elapsed yet
        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }
}
