// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.examples.types.ApprovalRequest;
import software.amazon.lambda.durable.retry.RetryDecision;

/**
 * Example demonstrating {@code context.withRetry} with {@code context.waitForCallback}.
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
    public String handleRequest(ApprovalRequest input, DurableContext context) {
        // Step 1: Prepare the approval request
        var prepared = context.step(
                "prepare",
                String.class,
                stepCtx -> "Approval for: " + input.description() + " ($" + input.amount() + ")");

        // Step 2: waitForCallback with retry — if the external system fails, try again with a fresh callback
        var approvalResult = context.withRetry(
                null,
                (attempt, ctx) -> ctx.waitForCallback(
                        "approval-" + attempt, String.class, (callbackId, stepCtx) -> stepCtx.getLogger()
                                .info("Attempt {}: sending callback {} to approval system", attempt, callbackId)),
                WithRetryConfig.builder()
                        .retryStrategy((error, attempt) -> attempt < MAX_ATTEMPTS
                                ? RetryDecision.retry(Duration.ofSeconds(2))
                                : RetryDecision.fail())
                        .build());

        // Step 3: Process the result
        return context.step("process-result", String.class, stepCtx -> prepared + " - Result: " + approvalResult);
    }
}
