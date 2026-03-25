// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class CustomPollingExampleTest {

    @Test
    void testCustomPollingExample() {
        var handler = new CustomPollingExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        // First run: executes validate step, then pending at wait
        var input = new GreetingRequest("world");
        var output1 = runner.run(input);

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());

        // Second run:
        runner.completeChainedInvoke("call-greeting", "\"hello\"");
        var output2 = runner.run(input);

        assertEquals(ExecutionStatus.SUCCEEDED, output2.getStatus());
        assertEquals("helloworld", output2.getResult(String.class));
    }
}
