// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/** Tests that nested step calling is properly rejected. */
class NestedStepIntegrationTest {

    @Test
    void nestedStepCalling() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // outer-step's supplier calls context.step() which internally calls stepAsync().get()
            // The get() is called from the outer step's thread (named "1-step"), triggering the check
            var future = context.stepAsync("outer-step", String.class, stepCtx -> {
                return context.step("inner-step", String.class, stepCtx2 -> "inner-result");
            });
            return future.get();
        });

        var result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("inner-result", result.getResult(String.class));
    }

    @Test
    void awaitingAsyncStepInsideSyncStep() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // Start async step from handler thread
            var asyncFuture = context.stepAsync("async-step", String.class, stepCtx -> "async-result");

            // Sync step tries to await the async step's result inside its supplier
            return context.step("sync-step", String.class, stepCtx -> {
                // This get() is called from sync-step's thread ("2-step"), which is not allowed
                return "combined: " + asyncFuture.get();
            });
        });

        var result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("combined: async-result", result.getResult(String.class));
    }
}
