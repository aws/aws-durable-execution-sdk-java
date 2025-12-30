// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Integration tests for StepSemantics when step succeeds but checkpoint fails */
class StepSemanticsIntegrationTest {

    @Test
    void testAtLeastOnceReExecutesAfterCheckpointLoss() {
        var executionCount = new AtomicInteger(0);

        var runner = new LocalDurableTestRunner<>(String.class, (input, context) -> {
            return context.step(
                    "step",
                    String.class,
                    () -> {
                        var count = executionCount.incrementAndGet();
                        return "Executed " + count + " times";
                    },
                    StepConfig.builder()
                            .semantics(StepSemantics.AT_LEAST_ONCE_PER_RETRY)
                            .build());
        });

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.simulateFireAndForgetCheckpointLoss("step");

        var result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, executionCount.get()); // Re-executed
    }

    @Test
    void testAtLeastOnceReExecutesAfterCheckpointFailure() {
        var executionCount = new AtomicInteger(0);

        var runner = new LocalDurableTestRunner<>(String.class, (input, context) -> {
            return context.step(
                    "step",
                    String.class,
                    () -> {
                        var count = executionCount.incrementAndGet();
                        return "Executed " + count + " times";
                    },
                    StepConfig.builder()
                            .semantics(StepSemantics.AT_LEAST_ONCE_PER_RETRY)
                            .build());
        });
        runner.setSkipTime(true);

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.resetCheckpointToStarted("step");
        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, executionCount.get()); // Re-executed
    }

    @Test
    void testAtMostOnceThrowsExceptionAfterCheckpointFailure() {
        var executionCount = new AtomicInteger(0);

        var runner = new LocalDurableTestRunner<>(String.class, (input, context) -> {
            return context.step(
                    "step",
                    String.class,
                    () -> {
                        executionCount.incrementAndGet();
                        return "Should not re-execute";
                    },
                    StepConfig.builder()
                            .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                            .build());
        });

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.resetCheckpointToStarted("step");

        var result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, executionCount.get()); // Not re-executed
    }
}
