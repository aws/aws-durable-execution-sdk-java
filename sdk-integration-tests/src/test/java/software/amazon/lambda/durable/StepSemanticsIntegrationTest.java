// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class StepSemanticsIntegrationTest {

    @Test
    void testAtLeastOnceCompletesSuccessfully() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        stepCtx -> {
                            executionCount.incrementAndGet();
                            return "result";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE_PER_RETRY)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testAtMostOnceCompletesSuccessfully() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        stepCtx -> {
                            executionCount.incrementAndGet();
                            return "result";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testAtMostOnceNoRetryFailsImmediately() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        stepCtx -> {
                            executionCount.incrementAndGet();
                            throw new RuntimeException("Always fails");
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testDefaultSemanticsPerRetryIsAtLeastOnce() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step("my-step", String.class, stepCtx -> {
                    executionCount.incrementAndGet();
                    return "result";
                }));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testAtLeastOnceReExecutesAfterCheckpointLoss() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "step",
                        String.class,
                        stepCtx -> {
                            var count = executionCount.incrementAndGet();
                            return "Executed " + count + " times";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE_PER_RETRY)
                                .build()));

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.simulateFireAndForgetCheckpointLoss("step");

        var result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, executionCount.get());
    }

    @Test
    void testAtLeastOnceReExecutesAfterCheckpointFailure() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "step",
                        String.class,
                        stepCtx -> {
                            var count = executionCount.incrementAndGet();
                            return "Executed " + count + " times";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE_PER_RETRY)
                                .build()));

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.resetCheckpointToStarted("step");
        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, executionCount.get());
    }

    @Test
    void testAtMostOnceRetriesAfterInterruption() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "step",
                        String.class,
                        stepCtx -> {
                            executionCount.incrementAndGet();
                            return "result";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                                .build()));

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.resetCheckpointToStarted("step");

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, executionCount.get());
    }
}
