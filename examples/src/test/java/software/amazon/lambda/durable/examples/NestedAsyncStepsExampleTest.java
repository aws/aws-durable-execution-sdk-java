// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class NestedAsyncStepsExampleTest {

    @Test
    void testNestedManyAsyncSteps_50thFibonacci() {
        var handler = new NestedAsyncStepsExample();
        var runner = LocalDurableTestRunner.create(NestedAsyncStepsExample.Input.class, handler);

        var input = new NestedAsyncStepsExample.Input(50);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(String.class);
        assertNotNull(output);
        assertTrue(output.contains("50 async steps"));

        // 50th Fibonacci number is 12,586,269,025
        assertTrue(output.contains("12586269025"));
    }

    @Test
    void testNestedManyAsyncSteps_100thFibonacci() {
        var handler = new NestedAsyncStepsExample();
        var runner = LocalDurableTestRunner.create(NestedAsyncStepsExample.Input.class, handler);

        var input = new NestedAsyncStepsExample.Input(100);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // 100th Fibonacci number 354,224,848,179,261,915,075 > Java Long.MAX_VALUE 9,223,372,036,854,775,807
        assertTrue(result.getError().isPresent());
        assertTrue(result.getError().get().errorMessage().contains("long overflow"));
    }
}
