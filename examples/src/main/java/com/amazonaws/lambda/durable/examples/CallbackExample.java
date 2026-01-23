// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.CallbackConfig;
import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import java.time.Duration;

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
        var prepared = context.step("prepare", String.class, () -> {
            return "Approval request for: " + input.description() + " ($" + input.amount() + ")";
        });

        // Step 2: Create callback for external approval
        // Configure with 5min timeout
        var config = CallbackConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .build();

        var callback = context.createCallback("approval", String.class, config);

        // Step 2.5: Log AWS CLI command to complete the callback
        context.step("log-callback-command", Void.class, () -> {
            var callbackId = callback.callbackId();
            var command = String.format(
                "aws lambda send-durable-execution-callback-success --callback-id %s --payload '{\"result\":\"approved\"}'",
                callbackId
            );
            context.getLogger().info("To complete this callback, run: {}", command);
            return null;
        });

        // Step 3: Wait for external approval (suspends execution)
        var approvalResult = callback.future().get();

        // Step 4: Process the approval
        var result = context.step("process-approval", String.class, () -> {
            return prepared + " - " + approvalResult;
        });

        return result;
    }
}

/** Input for the approval workflow. */
record ApprovalRequest(String description, double amount) {}
