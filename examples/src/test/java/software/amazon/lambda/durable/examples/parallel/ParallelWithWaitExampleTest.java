// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ParallelWithWaitExampleTest {
    @Test
    void completesAfterManuallyAdvancingWaits() {
        var handler = new ParallelWithWaitExample();
        var runner = LocalDurableTestRunner.create(ParallelWithWaitExample.Input.class, handler);

        var input = new ParallelWithWaitExample.Input("user-456", "world");

        // First run suspends on wait branches
        var first = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, first.getStatus());

        // Advance waits and re-run to completion
        runner.advanceTime();
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelWithWaitExample.Output.class);
        assertEquals(List.of("email:world", "sms:world", "push:world"), output.deliveries());
        assertEquals(3, output.success());
    }
}
