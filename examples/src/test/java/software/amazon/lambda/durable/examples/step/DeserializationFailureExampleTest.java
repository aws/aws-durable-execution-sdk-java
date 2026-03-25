// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.step;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class DeserializationFailureExampleTest {

    @Test
    void testDeserializationExample() {
        var handler = new DeserializationFailureExample();

        // Create test runner from handler (automatically extracts config)
        var runner = LocalDurableTestRunner.create(String.class, handler);

        // Run with input
        var result = runner.runUntilComplete("test-input");

        // Verify result
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // assert StepFailedException is thrown when SerDes fails to deserialize the exception
        assertEquals(
                "StepFailedException:Step failed with error of type java.lang.RuntimeException. Message: this is a test",
                result.getResult(String.class));
    }
}
