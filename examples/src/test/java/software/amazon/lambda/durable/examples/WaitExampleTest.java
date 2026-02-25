// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class WaitExampleTest {

    @Test
    void testWaitExampleStartsAndWaits() {
        var handler = new WaitExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Bob");

        // First run - executes first step and hits first wait
        var result = runner.run(input);

        // Should be PENDING because of wait operation
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Note: In real Lambda, the function would be re-invoked after the wait period
        // The LocalDurableTestRunner demonstrates the wait behavior but doesn't simulate time
    }
}
