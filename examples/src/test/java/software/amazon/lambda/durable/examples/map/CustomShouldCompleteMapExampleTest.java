// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class CustomShouldCompleteMapExampleTest {

    @Test
    void testCustomShouldCompleteSucceedsAfterRequiredResponses() {
        var handler = new CustomShouldCompleteMapExample();
        var runner = LocalDurableTestRunner.create(CustomShouldCompleteMapExample.Input.class, handler);

        var input = new CustomShouldCompleteMapExample.Input(List.of("primary", "secondary", "bad-cache"), 2, 2);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        var output = result.getResult(CustomShouldCompleteMapExample.Output.class);
        assertEquals("CUSTOM_COMPLETION_SUCCEEDED", output.completionStatus());
        assertTrue(output.completionSucceeded());
        assertEquals(List.of("response:primary", "response:secondary"), output.responses());
        assertEquals(0, output.failed());
        assertEquals(1, output.skipped());
    }

    @Test
    void testCustomShouldCompleteCanCompleteAsFailed() {
        var handler = new CustomShouldCompleteMapExample();
        var runner = LocalDurableTestRunner.create(CustomShouldCompleteMapExample.Input.class, handler);

        var input = new CustomShouldCompleteMapExample.Input(List.of("bad-primary", "bad-secondary", "primary"), 2, 2);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        var output = result.getResult(CustomShouldCompleteMapExample.Output.class);
        assertEquals("CUSTOM_COMPLETION_FAILED", output.completionStatus());
        assertFalse(output.completionSucceeded());
        assertTrue(output.responses().isEmpty());
        assertEquals(2, output.failed());
        assertEquals(1, output.skipped());
    }

    @Test
    void testReplay() {
        var handler = new CustomShouldCompleteMapExample();
        var runner = LocalDurableTestRunner.create(CustomShouldCompleteMapExample.Input.class, handler);

        var input = new CustomShouldCompleteMapExample.Input(List.of("primary", "secondary", "bad-cache"), 2, 2);
        var result1 = runner.runUntilComplete(input);
        var result2 = runner.runUntilComplete(input);

        assertEquals(
                result1.getResult(CustomShouldCompleteMapExample.Output.class),
                result2.getResult(CustomShouldCompleteMapExample.Output.class));
    }
}
