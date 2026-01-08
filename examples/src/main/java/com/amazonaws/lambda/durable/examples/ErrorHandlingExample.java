// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.StepSemantics;
import com.amazonaws.lambda.durable.exception.StepFailedException;
import com.amazonaws.lambda.durable.exception.StepInterruptedException;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating error handling patterns with the Durable Execution SDK.
 *
 * <p>This example shows how to handle:
 * <ul>
 *   <li>{@link StepFailedException} - when a step exhausts all retry attempts
 *   <li>{@link StepInterruptedException} - when an AT_MOST_ONCE step is interrupted
 * </ul>
 *
 * <p>Note: {@code NonDeterministicExecutionException} is thrown by the SDK when code changes
 * between executions (e.g., step order/names changed). It should be fixed in code, not caught.
 */
public class ErrorHandlingExample extends DurableHandler<String, String> {

    private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingExample.class);

    @Override
    public String handleRequest(String input, DurableContext context) {
        // Example 1: Catching StepFailedException with fallback logic
        String primaryResult;
        try {
            primaryResult = context.step(
                    "call-primary-service",
                    String.class,
                    () -> {
                        throw new RuntimeException("Primary service unavailable");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        } catch (StepFailedException e) {
            logger.warn("Primary service failed, using fallback: {}", e.getMessage());
            primaryResult = context.step(
                    "call-fallback-service",
                    String.class,
                    () -> "fallback-result");
        }

        // Example 2: Handling StepInterruptedException for AT_MOST_ONCE operations
        // StepInterruptedException is thrown when an AT_MOST_ONCE step was started
        // but the function was interrupted before the step completed.
        // In normal execution, this step succeeds. The catch block handles the
        // interruption scenario that occurs during replay after an unexpected termination.
        String paymentResult;
        try {
            paymentResult = context.step(
                    "charge-payment",
                    String.class,
                    () -> "payment-" + input,
                    StepConfig.builder()
                            .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                            .build());
        } catch (StepInterruptedException e) {
            logger.warn("Payment step interrupted, checking external status: {}", e.getOperationId());
            // In real code: check payment provider for transaction status
            // If payment went through, return success; otherwise, handle appropriately
            paymentResult = context.step(
                    "verify-payment-status",
                    String.class,
                    () -> "verified-payment");
        }

        return "Completed: " + primaryResult + ", " + paymentResult;
    }
}
