// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class WaitForConditionExampleTest {

    @Test
    void testWaitForConditionExample() {
        var handler = new WaitForConditionExample();
        var runner = LocalDurableTestRunner.create(Integer.class, handler);

        var result = runner.runUntilComplete(123);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(4, result.getResult(Integer.class));
    }
}
