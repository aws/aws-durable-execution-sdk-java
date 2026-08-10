// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.examples.types.ApprovalRequest;
import software.amazon.lambda.durable.operation.DurableCallbackOperation;
import software.amazon.lambda.durable.operation.DurableCallbackOperation.CallbackConfig;
import software.amazon.lambda.durable.operation.DurableStepOperation;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation.WaitForCallbackContext;

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
    public String handleRequest(ApprovalRequest input) {
        // Step 1: Prepare the approval request
        var prepared = DurableStepOperation.step(
                "prepare",
                String.class,
                () -> "Approval request for: " + input.description() + " ($" + input.amount() + ")");

        // Step 2: Create callback for external approval
        // Use timeout from input if provided, otherwise default to 5 minutes
        var timeout =
                input.timeoutSeconds() != null ? Duration.ofSeconds(input.timeoutSeconds()) : Duration.ofMinutes(5);

        var config = CallbackConfig.builder().timeout(timeout).build();

        var preapprovalCallback =
                DurableWaitForCallbackOperation.waitForCallbackAsync("preapproval", String.class, () -> {
                    var callbackId = WaitForCallbackContext.getCurrentContext().getCallbackId();
                    StepContext.getCurrentContext()
                            .getLogger()
                            .info("Sending callback {} to preapproval system", callbackId);
                });

        var callback = DurableCallbackOperation.createCallback("approval", String.class, config);

        // Step 2.5: Log AWS CLI command to complete the callback
        DurableStepOperation.step("log-callback-command", Void.class, () -> {
            var callbackId = callback.callbackId();
            // The result must be base64-encoded JSON
            var command = String.format(
                    "aws lambda send-durable-execution-callback-success --callback-id %s --result $(echo -n '\"approved\"' | base64)",
                    callbackId);
            StepContext.getCurrentContext().getLogger().info("To complete this callback, run: {}", command);
            return null;
        });

        var preapprovalResult = preapprovalCallback.get();

        // Step 3: Wait for external approval (suspends execution)
        var approvalResult = callback.get();

        // Step 4: Process the approval
        return DurableStepOperation.step(
                "process-approval", String.class, () -> prepared + " - " + preapprovalResult + " - " + approvalResult);
    }
}
