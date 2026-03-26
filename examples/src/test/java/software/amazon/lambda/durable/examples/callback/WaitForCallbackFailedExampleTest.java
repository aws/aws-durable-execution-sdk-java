// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.callback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.ApprovalRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class WaitForCallbackFailedExampleTest {

    @Test
    void testWaitForCallbackFailedExample() {
        var handler = new WaitForCallbackFailedExample();
        var runner = LocalDurableTestRunner.create(ApprovalRequest.class, handler);

        var input = new ApprovalRequest("New laptop", 1500.00);

        // First run - prepares request and creates callback, then suspends
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Verify the callback was created
        assertEquals(
                "CallbackSubmitterException:Step failed with error of type java.lang.RuntimeException. Message: Submitter failed with an exception",
                result.getResult(String.class));
    }
}
