// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.invoke;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class RetryInvokeExampleTest {

    @Test
    void succeedsOnFirstAttempt() {
        var handler = new RetryInvokeExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);
        var input = new GreetingRequest("world");

        // First run — starts the invoke, suspends waiting for result
        var result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete the first invoke attempt
        runner.completeChainedInvoke("call-greeting-1", "\"hello world\"");
        result = runner.run(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("hello world", result.getResult(String.class));
    }

    @Test
    void retriesAfterFirstAttemptFails() {
        var handler = new RetryInvokeExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);
        var input = new GreetingRequest("world");

        // First run — starts invoke attempt 1
        var result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail the first invoke attempt
        runner.failChainedInvoke(
                "call-greeting-1",
                ErrorObject.builder()
                        .errorType("TransientError")
                        .errorMessage("Service unavailable")
                        .build());

        // Second run — processes the failure, does backoff wait, starts invoke attempt 2
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past the backoff wait
        runner.advanceTime();
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete the second invoke attempt
        runner.completeChainedInvoke("call-greeting-2", "\"hello on retry\"");
        result = runner.run(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("hello on retry", result.getResult(String.class));
    }

    @Test
    void failsAfterAllRetriesExhausted() {
        var handler = new RetryInvokeExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);
        var input = new GreetingRequest("world");

        // First run — starts invoke attempt 1
        var result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail attempt 1
        runner.failChainedInvoke(
                "call-greeting-1",
                ErrorObject.builder()
                        .errorType("TransientError")
                        .errorMessage("fail 1")
                        .build());
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff wait 1
        runner.advanceTime();
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail attempt 2
        runner.failChainedInvoke(
                "call-greeting-2",
                ErrorObject.builder()
                        .errorType("TransientError")
                        .errorMessage("fail 2")
                        .build());
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff wait 2
        runner.advanceTime();
        result = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail attempt 3 — this is the last attempt, retryStrategy returns fail()
        runner.failChainedInvoke(
                "call-greeting-3",
                ErrorObject.builder()
                        .errorType("TransientError")
                        .errorMessage("fail 3")
                        .build());
        result = runner.run(input);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void suspendsOnFirstRun() {
        var handler = new RetryInvokeExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);
        var input = new GreetingRequest("test");

        var result = runner.run(input);

        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }
}
