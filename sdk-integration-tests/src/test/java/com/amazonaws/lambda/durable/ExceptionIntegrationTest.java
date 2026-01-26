// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.lambda.durable.exception.StepInterruptedException;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Integration tests for exception handling scenarios documented in the README. */
class ExceptionIntegrationTest {

    @Test
    void testStepFailedExceptionThrownAfterRetryExhaustion() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.step(
                    "always-fails",
                    String.class,
                    () -> {
                        throw new RuntimeException("Service unavailable");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        });

        var result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void testStepFailedExceptionCanBeCaughtWithFallback() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            try {
                return ctx.step(
                        "primary",
                        String.class,
                        () -> {
                            throw new RuntimeException("Primary failed");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build());
            } catch (RuntimeException e) {
                return ctx.step("fallback", String.class, () -> "fallback-result");
            }
        });

        var result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("fallback-result", result.getResult(String.class));
    }

    @Test
    void testOriginalExceptionTypeIsPreserved() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            ctx.step(
                    "throws-illegal-arg",
                    String.class,
                    () -> {
                        throw new IllegalArgumentException("Invalid parameter");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
            return "should-not-reach";
        });

        // First run - exception is thrown and checkpointed
        var result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // Verify the operation failed with the correct exception type
        var failedOp = result.getOperation("throws-illegal-arg");
        assertNotNull(failedOp);
        var error = failedOp.getError();
        assertNotNull(error);
        assertEquals("java.lang.IllegalArgumentException", error.errorType());
        assertEquals("Invalid parameter", error.errorMessage());

        // Verify stackTrace is preserved
        assertNotNull(error.stackTrace());
        assertTrue(error.stackTrace().size() > 0, "Stack trace should not be empty");

        // Verify errorData contains serialized exception
        assertNotNull(error.errorData());
        assertTrue(error.errorData().contains("Invalid parameter"), "errorData should contain the exception message");
    }

    @Test
    void testOriginalExceptionTypeCanBeCaughtSpecifically() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            try {
                return ctx.step(
                        "throws-illegal-state",
                        String.class,
                        () -> {
                            throw new IllegalStateException("Invalid state");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build());
            } catch (IllegalStateException e) {
                // Catch specific exception type
                return ctx.step("handle-illegal-state", String.class, () -> "recovered-from-illegal-state");
            } catch (Exception e) {
                // This should NOT be caught
                return ctx.step("handle-illegal-arg", String.class, () -> "recovered-from-exception");
            }
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("recovered-from-illegal-state", result.getResult(String.class));
    }

    @Test
    void testCustomExceptionTypeIsPreserved() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            ctx.step(
                    "throws-custom",
                    String.class,
                    () -> {
                        throw new CustomBusinessException("Business rule violated", 42);
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
            return "should-not-reach";
        });

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // Verify the operation failed with the correct exception type
        var failedOp = result.getOperation("throws-custom");
        assertNotNull(failedOp);
        var error = failedOp.getError();
        assertNotNull(error);
        assertTrue(error.errorType().contains("CustomBusinessException"));
        assertEquals("Business rule violated", error.errorMessage());
    }

    @Test
    void testStepInterruptedExceptionForAtMostOnceAfterCheckpointLoss() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.step(
                    "at-most-once-step",
                    String.class,
                    () -> {
                        executionCount.incrementAndGet();
                        return "result";
                    },
                    StepConfig.builder()
                            .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                            .build());
        });

        // First run succeeds
        runner.run("test");
        assertEquals(1, executionCount.get());

        // Simulate checkpoint loss (step started but result not saved)
        runner.resetCheckpointToStarted("at-most-once-step");

        // Second run should fail with StepInterruptedException (not re-execute)
        var result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, executionCount.get()); // Should NOT have re-executed
        assertEquals(result.getError().get().errorType(), StepInterruptedException.class.getName());
    }

    @Test
    void testStepInterruptedExceptionCanBeCaughtForRecovery() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            try {
                return ctx.step(
                        "payment",
                        String.class,
                        () -> {
                            executionCount.incrementAndGet();
                            return "payment-success";
                        },
                        StepConfig.builder()
                                .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                                .build());
            } catch (StepInterruptedException e) {
                // Recovery: check external status and return verified result
                return ctx.step("verify-payment", String.class, () -> "verified-payment");
            }
        });

        // First run succeeds
        runner.run("test");

        // Simulate interruption (step started but result not checkpointed)
        runner.resetCheckpointToStarted("payment");

        // Second run catches exception and recovers
        var result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("verified-payment", result.getResult(String.class));
    }

    @Test
    void testNonDeterministicExceptionOnStepNameChange() {
        var useNewName = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var stepName = useNewName.get() == 0 ? "original-name" : "changed-name";
            return ctx.step(stepName, String.class, () -> "result");
        });

        // First run with original name
        runner.run("test");

        // Change step name for replay
        useNewName.set(1);

        // Replay should detect non-determinism
        var result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    // Custom exception for testing exception preservation
    public static class CustomBusinessException extends RuntimeException {
        private final int errorCode;

        public CustomBusinessException(String message, int errorCode) {
            super(message);
            this.errorCode = errorCode;
        }

        public int getErrorCode() {
            return errorCode;
        }
    }
}
