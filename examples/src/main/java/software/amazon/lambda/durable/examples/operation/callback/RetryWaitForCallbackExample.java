// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.callback;

import static software.amazon.lambda.durable.operation.DurableStepOperation.step;
import static software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation.waitForCallback;
import static software.amazon.lambda.durable.operation.DurableWithRetryOperation.withRetry;

import java.time.Duration;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.examples.types.ApprovalRequest;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation.WaitForCallbackContext;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation.WithRetryConfig;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation.WithRetryContext;
import software.amazon.lambda.durable.retry.RetryDecision;

/**
 * Example demonstrating {@code withRetry} with {@code waitForCallback}.
 *
 * <p>Submits an approval request to an external system via a callback. If the callback fails (e.g., the external system
 * rejects the request), the helper retries the entire waitForCallback cycle — creating a fresh callback with a new ID
 * each time.
 *
 * <p>Each attempt uses a unique callback name ({@code "approval-1"}, {@code "approval-2"}, etc.) so the execution
 * history stays clean and replay-safe. A {@code null} name is used, so attempts are grouped under a default-named child
 * context.
 */
public class RetryWaitForCallbackExample extends DurableHandler<ApprovalRequest, String> {

    private static final int MAX_ATTEMPTS = 3;

    @Override
    public String handleRequest(ApprovalRequest input) {
        // Step 1: Prepare the approval request
        var prepared = step(
                "prepare", String.class, () -> "Approval for: " + input.description() + " ($" + input.amount() + ")");

        // Step 2: waitForCallback with retry — if the external system fails, try again with a fresh callback
        var approvalResult = withRetry(
                null,
                () -> {
                    var attempt = WithRetryContext.getCurrentContext().getAttempt();
                    return waitForCallback("approval-" + attempt, String.class, () -> StepContext.getCurrentContext()
                            .getLogger()
                            .info(
                                    "Attempt {}: sending callback {} to approval system",
                                    attempt,
                                    WaitForCallbackContext.getCurrentContext().getCallbackId()));
                },
                WithRetryConfig.builder()
                        .retryStrategy((error, attempt) -> attempt < MAX_ATTEMPTS
                                ? RetryDecision.retry(Duration.ofSeconds(2))
                                : RetryDecision.fail())
                        .build());

        // Step 3: Process the result
        return step("process-result", String.class, () -> prepared + " - Result: " + approvalResult);
    }
}
