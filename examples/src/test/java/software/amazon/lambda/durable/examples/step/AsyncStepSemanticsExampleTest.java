// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.step;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class AsyncStepSemanticsExampleTest {

    @Test
    void replayKeepsTheIdempotencyKey() {
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, new AsyncStepSemanticsExample());
        var input = new GreetingRequest("Alice");

        var first = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, first.getStatus());
        var key = runner.getOperation("create-idempotency-key").getStepResult(String.class);

        runner.advanceTime();
        var second = runner.run(input);

        assertEquals(ExecutionStatus.SUCCEEDED, second.getStatus());
        assertEquals("Hello, Alice (" + key + ")!", second.getResult(String.class));
    }
}
