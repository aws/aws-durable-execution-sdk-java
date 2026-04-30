// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.callback;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.examples.types.ApprovalRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class RetryWaitForCallbackExampleTest {

    @Test
    void succeedsOnFirstAttempt() {
        var handler = new RetryWaitForCallbackExample();
        var runner = LocalDurableTestRunner.create(ApprovalRequest.class, handler);
        var input = new ApprovalRequest("New laptop", 1500.00);

        // First run — prepares request, starts waitForCallback, suspends
        var result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete the callback (waitForCallback names it "approval-1-callback" internally)
        var callbackId = runner.getCallbackId("approval-1-callback");
        assertNotNull(callbackId, "Callback 'approval-1-callback' should have been created");
        runner.completeCallback(callbackId, "\"Approved by manager\"");

        // Run to completion
        result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(
                "Approval for: New laptop ($1500.0) - Result: Approved by manager", result.getResult(String.class));
    }

    @Test
    void retriesAfterFirstCallbackFails() {
        var handler = new RetryWaitForCallbackExample();
        var runner = LocalDurableTestRunner.create(ApprovalRequest.class, handler);
        var input = new ApprovalRequest("Server upgrade", 5000.00);

        // First run — prepares, starts waitForCallback attempt 1, suspends
        var result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail the first callback
        var callbackId1 = runner.getCallbackId("approval-1-callback");
        assertNotNull(callbackId1);
        runner.failCallback(
                callbackId1,
                ErrorObject.builder()
                        .errorType("RejectedError")
                        .errorMessage("Rejected by first reviewer")
                        .build());

        // Run — processes failure, hits backoff wait, suspends
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past the backoff wait
        runner.advanceTime();

        // Run — starts waitForCallback attempt 2, suspends
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete the second callback
        var callbackId2 = runner.getCallbackId("approval-2-callback");
        assertNotNull(callbackId2, "Callback 'approval-2-callback' should have been created after retry");
        runner.completeCallback(callbackId2, "\"Approved on second try\"");

        // Run to completion
        result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(
                "Approval for: Server upgrade ($5000.0) - Result: Approved on second try",
                result.getResult(String.class));
    }

    @Test
    void failsAfterAllRetriesExhausted() {
        var handler = new RetryWaitForCallbackExample();
        var runner = LocalDurableTestRunner.create(ApprovalRequest.class, handler);
        var input = new ApprovalRequest("Expensive item", 10000.00);

        // First run — starts waitForCallback attempt 1
        var result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail callback attempt 1
        var callbackId1 = runner.getCallbackId("approval-1-callback");
        runner.failCallback(
                callbackId1,
                ErrorObject.builder()
                        .errorType("Rejected")
                        .errorMessage("fail 1")
                        .build());
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff 1, run to start attempt 2
        runner.advanceTime();
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail callback attempt 2
        var callbackId2 = runner.getCallbackId("approval-2-callback");
        runner.failCallback(
                callbackId2,
                ErrorObject.builder()
                        .errorType("Rejected")
                        .errorMessage("fail 2")
                        .build());
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff 2, run to start attempt 3
        runner.advanceTime();
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail callback attempt 3 — last attempt, retryStrategy returns fail()
        var callbackId3 = runner.getCallbackId("approval-3-callback");
        runner.failCallback(
                callbackId3,
                ErrorObject.builder()
                        .errorType("Rejected")
                        .errorMessage("fail 3")
                        .build());
        result = runner.run(input);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void suspendsOnFirstRun() {
        var handler = new RetryWaitForCallbackExample();
        var runner = LocalDurableTestRunner.create(ApprovalRequest.class, handler);
        var input = new ApprovalRequest("Test item", 100.00);

        var result = runner.run(input);

        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }
}
