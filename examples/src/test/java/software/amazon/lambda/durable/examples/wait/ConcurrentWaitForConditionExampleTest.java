// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.wait;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ConcurrentWaitForConditionExampleTest {

    @Test
    void testConcurrentWaitForConditionExample() {
        var handler = new ConcurrentWaitForConditionExample();
        var runner = LocalDurableTestRunner.create(ConcurrentWaitForConditionExample.Input.class, handler);

        var result = runner.runUntilComplete(new ConcurrentWaitForConditionExample.Input(3, 100, 50));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var allOperationsOutput = result.getResult(String.class);
        var operationResults = allOperationsOutput.split(" \\| ");
        assertEquals(100, operationResults.length);
        for (var operationResult : operationResults) {
            assertEquals("3", operationResult);
        }
    }
}
