// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepConfig;
import software.amazon.lambda.durable.StepSemantics;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * Example demonstrating error handling patterns with the Durable Execution SDK.
 *
 * <p>This example shows how to handle:
 *
 * <ul>
 *   <li>{@link StepFailedException} - when a step exhausts all retry attempts
 *   <li>{@link StepInterruptedException} - when an AT_MOST_ONCE step is interrupted
 *   <li>Custom exceptions - original exception types are preserved and can be caught directly
 * </ul>
 *
 * <p>Note: {@code NonDeterministicExecutionException} is thrown by the SDK when code changes between executions (e.g.,
 * step order/names changed). It should be fixed in code, not caught.
 */
public class ErrorHandlingExample extends DurableHandler<Object, String> {

    private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingExample.class);

    /** Custom exception to demonstrate that original exception types are preserved across checkpoints. */
    public static class ServiceUnavailableException extends RuntimeException {
        private String serviceName;

        /** Default constructor required for Jackson deserialization. */
        public ServiceUnavailableException() {
            super();
        }

        public ServiceUnavailableException(String serviceName, String message) {
            super(message);
            this.serviceName = serviceName;
        }

        public String getServiceName() {
            return serviceName;
        }
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        // Example 1: Catching a custom exception type with fallback logic
        // The SDK preserves the original exception type, so you can catch specific exceptions directly.
        // NOTE: Exception type needs to be serializable by your SerDes implementation.
        String primaryResult;
        try {
            primaryResult = context.step(
                    "call-primary-service",
                    String.class,
                    () -> {
                        throw new ServiceUnavailableException("primary-api", "Primary service unavailable");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        } catch (ServiceUnavailableException e) {
            // Catch the specific custom exception type - the SDK reconstructs the original exception
            logger.warn("Service '{}' unavailable, using fallback: {}", e.getServiceName(), e.getMessage());
            primaryResult = context.step("call-fallback-service", String.class, () -> "fallback-result");
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
            logger.warn(
                    "Payment step interrupted, checking external status: {}",
                    e.getOperation().id());
            // In real code: check payment provider for transaction status
            // If payment went through, return success; otherwise, handle appropriately
            paymentResult = context.step("verify-payment-status", String.class, () -> "verified-payment");
        }

        return "Completed: " + primaryResult + ", " + paymentResult;
    }
}
