// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StepSemanticsTest {

    @Test
    void testAtLeastOnceCompletesSuccessfully() {
        var executionCount = new AtomicInteger(0);

        var runner = new LocalDurableTestRunner<>(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        () -> {
                            executionCount.incrementAndGet();
                            return "result";
                        },
                        StepConfig.builder()
                                .semantics(StepSemantics.AT_LEAST_ONCE_PER_RETRY)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testAtMostOnceCompletesSuccessfully() {
        var executionCount = new AtomicInteger(0);

        var runner = new LocalDurableTestRunner<>(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        () -> {
                            executionCount.incrementAndGet();
                            return "result";
                        },
                        StepConfig.builder()
                                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testAtMostOnceNoRetryFailsImmediately() {
        var executionCount = new AtomicInteger(0);

        var runner = new LocalDurableTestRunner<>(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        () -> {
                            executionCount.incrementAndGet();
                            throw new RuntimeException("Always fails");
                        },
                        StepConfig.builder()
                                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testDefaultSemanticsIsAtLeastOnce() {
        var executionCount = new AtomicInteger(0);

        var runner = new LocalDurableTestRunner<>(
                String.class,
                (input, ctx) -> ctx.step("my-step", String.class, () -> {
                    executionCount.incrementAndGet();
                    return "result";
                }));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }
}
