// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepConfig;
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
                        () -> {
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
                        () -> {
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
                        () -> {
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
}
