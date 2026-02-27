// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import software.amazon.lambda.durable.CallbackConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * Example demonstrating callback operations for external system integration.
 *
 * <p>This handler demonstrates a human approval workflow:
 *
 * <ol>
 *   <li>Prepare the request for approval
 *   <li>Create a callback and send the callback ID to an external approval system
 *   <li>Suspend execution until the external system responds
 *   <li>Process the approval result
 * </ol>
 *
 * <p>External systems respond using AWS Lambda APIs:
 *
 * <ul>
 *   <li>{@code SendDurableExecutionCallbackSuccess} - approve with result
 *   <li>{@code SendDurableExecutionCallbackFailure} - reject with error
 *   <li>{@code SendDurableExecutionCallbackHeartbeat} - keep callback alive
 * </ul>
 */
public class CallbackExample extends DurableHandler<ApprovalRequest, String> {

    @Override
    public String handleRequest(ApprovalRequest input, DurableContext context) {
        // Step 1: Prepare the approval request
        var prepared = context.step(
                "prepare",
                String.class,
                () -> "Approval request for: " + input.description() + " ($" + input.amount() + ")");

        // Step 2: Create callback for external approval
        // Use timeout from input if provided, otherwise default to 5 minutes
        var timeout =
                input.timeoutSeconds() != null ? Duration.ofSeconds(input.timeoutSeconds()) : Duration.ofMinutes(5);

        var config = CallbackConfig.builder().timeout(timeout).build();

        var callback = context.createCallback("approval", String.class, config);

        // Step 2.5: Log AWS CLI command to complete the callback
        context.step("log-callback-command", Void.class, ctx -> {
            var callbackId = callback.callbackId();
            // The result must be base64-encoded JSON
            var command = String.format(
                    "aws lambda send-durable-execution-callback-success --callback-id %s --result $(echo -n '\"approved\"' | base64)",
                    callbackId);
            ctx.getLogger().info("To complete this callback, run: {}", command);
            return null;
        });

        // Step 3: Wait for external approval (suspends execution)
        var approvalResult = callback.get();

        // Step 4: Process the approval
        var result = context.step("process-approval", String.class, () -> {
            return prepared + " - " + approvalResult;
        });

        return result;
    }
}

/** Input for the approval workflow. */
record ApprovalRequest(String description, double amount, Integer timeoutSeconds) {
    // Convenience constructor for default timeout
    public ApprovalRequest(String description, double amount) {
        this(description, amount, null);
    }
}
