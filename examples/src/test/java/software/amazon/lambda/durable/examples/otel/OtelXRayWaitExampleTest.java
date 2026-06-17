// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class OtelXRayWaitExampleTest {

    @Test
    void testFirstInvocation_suspendsOnWait() {
        var handler = new OtelXRayWaitExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.run(new GreetingRequest("Alice"));

        // First invocation hits the wait and suspends
        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }

    @Test
    void testFullExecution_completesAfterWait() {
        var handler = new OtelXRayWaitExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertTrue(
                result.getResult(String.class).contains("Resumed and completed"),
                "Expected result to contain 'Resumed and completed', got: " + result.getResult(String.class));
    }

    @Test
    void testReplay_returnsSameResult() {
        var handler = new OtelXRayWaitExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Bob");
        var result1 = runner.runUntilComplete(input);
        var result2 = runner.runUntilComplete(input);

        assertEquals(result1.getResult(String.class), result2.getResult(String.class));
    }
}
