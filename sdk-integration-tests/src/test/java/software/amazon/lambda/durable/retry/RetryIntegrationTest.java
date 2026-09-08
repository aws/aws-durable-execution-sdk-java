// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class RetryIntegrationTest {

    private AtomicInteger callCount;

    @BeforeEach
    void setUp() {
        callCount = new AtomicInteger(0);
    }

    @Test
    void testStepWithDefaultRetryStrategy_ShouldRetryOnFailure() {
        var handler = new DurableHandler<String, String>() {
            @Override
            public String handleRequest(String input, DurableContext context) {
                var config = StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build();

                return context.step(
                        "failing-step",
                        String.class,
                        stepCtx -> {
                            callCount.incrementAndGet();
                            throw new RuntimeException("Simulated failure");
                        },
                        config);
            }
        };

        var runner = LocalDurableTestRunner.create(String.class, handler);
        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.PENDING, result.getStatus());
        assertEquals(1, callCount.get());
    }

    @Test
    void testStepWithNoRetryStrategy_ShouldFailImmediately() {
        var handler = new DurableHandler<String, String>() {
            @Override
            public String handleRequest(String input, DurableContext context) {
                var config = StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build();

                return context.step(
                        "no-retry-step",
                        String.class,
                        stepCtx -> {
                            callCount.incrementAndGet();
                            throw new RuntimeException("Simulated failure");
                        },
                        config);
            }
        };

        var runner = LocalDurableTestRunner.create(String.class, handler);
        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, callCount.get());
    }

    @Test
    void testSuccessfulStepWithRetryConfig_ShouldNotTriggerRetry() {
        var handler = new DurableHandler<String, String>() {
            @Override
            public String handleRequest(String input, DurableContext context) {
                var config = StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build();

                return context.step(
                        "successful-step",
                        String.class,
                        stepCtx -> {
                            callCount.incrementAndGet();
                            return "success: " + input;
                        },
                        config);
            }
        };

        var runner = LocalDurableTestRunner.create(String.class, handler);
        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("success: test-input", result.getResult(String.class));
        assertEquals(1, callCount.get());
    }

    @Test
    void testStepContextAttemptNumbers_ShouldBeOneBased() {
        var attempts = new CopyOnWriteArrayList<Integer>();
        var handler = new DurableHandler<String, String>() {
            @Override
            public String handleRequest(String input, DurableContext context) {
                return context.step(
                        "retry-step",
                        String.class,
                        stepContext -> {
                            attempts.add(stepContext.getAttempt());
                            if (stepContext.getAttempt() == 1) {
                                throw new RuntimeException("Retry once");
                            }
                            return "success";
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                                .build());
            }
        };

        var runner = LocalDurableTestRunner.create(String.class, handler);
        var result = runner.runUntilComplete("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("success", result.getResult(String.class));
        assertEquals(List.of(1, 2), attempts);
    }
}
