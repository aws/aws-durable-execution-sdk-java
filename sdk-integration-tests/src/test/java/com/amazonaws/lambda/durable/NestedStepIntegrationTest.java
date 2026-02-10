// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;

/** Tests that nested step calling is properly rejected. */
class NestedStepIntegrationTest {

    @Test
    void nestedStepCallingThrowsIllegalStateException() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // outer-step's supplier calls context.step() which internally calls stepAsync().get()
            // The get() is called from the outer step's thread (named "1-step"), triggering the check
            var future = context.stepAsync("outer-step", String.class, () -> {
                return context.step("inner-step", String.class, () -> "inner-result");
            });
            return future.get();
        });

        var result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        var errorMessage = result.getError().get().errorMessage();
        assertTrue(
                errorMessage.contains("Nested STEP operation is not supported"),
                "Expected error about nested step calling, got: " + errorMessage);
    }

    @Test
    void awaitingAsyncStepInsideSyncStepThrowsIllegalStateException() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // Start async step from handler thread
            var asyncFuture = context.stepAsync("async-step", String.class, () -> "async-result");

            // Sync step tries to await the async step's result inside its supplier
            return context.step("sync-step", String.class, () -> {
                // This get() is called from sync-step's thread ("2-step"), which is not allowed
                return "combined: " + asyncFuture.get();
            });
        });

        var result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        var errorMessage = result.getError().get().errorMessage();
        assertTrue(
                errorMessage.contains("Nested STEP operation is not supported"),
                "Expected error about nested step calling, got: " + errorMessage);
    }
}
