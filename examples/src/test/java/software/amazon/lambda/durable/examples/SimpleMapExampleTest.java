// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class SimpleMapExampleTest {

    @Test
    void testSimpleMapExample() {
        var handler = new SimpleMapExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("Hello, Alice! | Hello, ALICE! | Hello, alice!", result.getResult(String.class));
    }

    @Test
    void testWithDefaultName() {
        var handler = new SimpleMapExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest());

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("Hello, World! | Hello, WORLD! | Hello, world!", result.getResult(String.class));
    }

    @Test
    void testReplay() {
        var handler = new SimpleMapExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Bob");
        var result1 = runner.runUntilComplete(input);
        assertEquals("Hello, Bob! | Hello, BOB! | Hello, bob!", result1.getResult(String.class));

        // Replay — should use cached results
        var result2 = runner.runUntilComplete(input);
        assertEquals(result1.getResult(String.class), result2.getResult(String.class));
    }
}
