// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class LoggingExampleTest {

    @Test
    void testLoggingExample() {
        var handler = new LoggingExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.run(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, ALICE!", result.getResult(String.class));
    }
}
