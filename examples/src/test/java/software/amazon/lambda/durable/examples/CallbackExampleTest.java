// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class CallbackExampleTest {

    @Test
    void testCallbackExampleSuspendsForApproval() {
        var handler = new CallbackExample();
        var runner = LocalDurableTestRunner.create(ApprovalRequest.class, handler);

        var input = new ApprovalRequest("New laptop", 1500.00);

        // First run - prepares request and creates callback, then suspends
        var result = runner.run(input);

        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Verify the callback was created
        var callbackOp = runner.getOperation("approval");
        assertNotNull(callbackOp);
        assertEquals(OperationType.CALLBACK, callbackOp.getType());
        assertEquals(OperationStatus.STARTED, callbackOp.getStatus());
    }

    @Test
    void testCallbackExampleCompletesAfterApproval() {
        var handler = new CallbackExample();
        var runner = LocalDurableTestRunner.create(ApprovalRequest.class, handler);

        var input = new ApprovalRequest("New laptop", 1500.00);

        // First run - suspends waiting for callback
        var result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Simulate external system approving the request
        var callbackId = runner.getCallbackId("approval");
        runner.completeCallback(callbackId, "\"Approved by manager\"");

        // Second run - callback complete, finishes processing
        result = runner.run(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(
                "Approval request for: New laptop ($1500.0) - Approved by manager", result.getResult(String.class));
    }
}
