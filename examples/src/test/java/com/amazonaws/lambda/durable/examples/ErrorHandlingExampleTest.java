// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;

class ErrorHandlingExampleTest {

    @Test
    void testErrorHandlingWithFallback() {
        var handler = new ErrorHandlingExample();
        var runner = LocalDurableTestRunner.create(String.class, handler);

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertTrue(result.getResult(String.class).contains("fallback-result"));
    }

    @Test
    void testPaymentStepCompletes() {
        var handler = new ErrorHandlingExample();
        var runner = LocalDurableTestRunner.create(String.class, handler);

        var result = runner.run("order-123");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        // Normal execution: payment step succeeds with "payment-order-123"
        assertTrue(result.getResult(String.class).contains("payment-order-123"));
    }

    @Test
    void testPaymentStepInterruptedRecovery() {
        var handler = new ErrorHandlingExample();
        var runner = LocalDurableTestRunner.create(String.class, handler);

        // First run: both steps complete normally
        var result1 = runner.run("order-456");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());

        // Simulate interruption: reset payment step to STARTED state
        runner.resetCheckpointToStarted("charge-payment");

        // Second run: StepInterruptedException is caught, recovery step executes
        var result2 = runner.run("order-456");

        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertTrue(result2.getResult(String.class).contains("verified-payment"));
    }
}
