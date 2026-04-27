// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.WithRetryHelper;

class RetryWaitForCallbackIntegrationTest {

    @Test
    void waitForCallbackSucceedsOnFirstAttempt() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) -> ctx.waitForCallback(
                                "approval-" + attempt, String.class, (callbackId, stepCtx) -> stepCtx.getLogger()
                                        .info("Submitting callback {}", callbackId)),
                        WithRetryConfig.builder()
                                .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(2)))
                                .build()));

        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // waitForCallback("approval-1", ...) creates "approval-1-callback" internally
        var callbackId = runner.getCallbackId("approval-1-callback");
        assertNotNull(callbackId);
        runner.completeCallback(callbackId, "\"approved\"");

        result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("approved", result.getResult(String.class));
    }

    @Test
    void waitForCallbackRetriesAfterFailure() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) -> ctx.waitForCallback(
                                "approval-" + attempt, String.class, (callbackId, stepCtx) -> stepCtx.getLogger()
                                        .info("Attempt {} callback {}", attempt, callbackId)),
                        WithRetryConfig.builder()
                                .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(2)))
                                .build()));

        // First run — starts waitForCallback attempt 1
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail attempt 1
        var callbackId1 = runner.getCallbackId("approval-1-callback");
        assertNotNull(callbackId1);
        runner.failCallback(
                callbackId1,
                ErrorObject.builder()
                        .errorType("Rejected")
                        .errorMessage("denied by reviewer")
                        .build());

        // Run — processes failure, hits backoff wait
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff
        runner.advanceTime();
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete attempt 2
        var callbackId2 = runner.getCallbackId("approval-2-callback");
        assertNotNull(callbackId2);
        runner.completeCallback(callbackId2, "\"approved on retry\"");

        result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("approved on retry", result.getResult(String.class));
    }

    @Test
    void waitForCallbackFailsAfterAllRetriesExhausted() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) ->
                                ctx.waitForCallback("approval-" + attempt, String.class, (callbackId, stepCtx) -> {}),
                        WithRetryConfig.builder()
                                .retryStrategy((error, attempt) ->
                                        attempt < 2 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail())
                                .build()));

        // Attempt 1
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        runner.failCallback(
                runner.getCallbackId("approval-1-callback"),
                ErrorObject.builder().errorMessage("fail 1").build());
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff
        runner.advanceTime();
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Attempt 2 — last attempt
        runner.failCallback(
                runner.getCallbackId("approval-2-callback"),
                ErrorObject.builder().errorMessage("fail 2").build());
        result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void waitForCallbackRetryWithStepsBeforeAndAfter() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var prefix = context.step("prepare", String.class, stepCtx -> "prepared");

            var callbackResult = WithRetryHelper.withRetry(
                    context,
                    (ctx, attempt) ->
                            ctx.waitForCallback("approval-" + attempt, String.class, (callbackId, stepCtx) -> {}),
                    WithRetryConfig.builder()
                            .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                            .build());

            return context.step("finalize", String.class, stepCtx -> prefix + " -> " + callbackResult + " -> done");
        });

        // First run — prepare completes, waitForCallback starts
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete callback
        var callbackId = runner.getCallbackId("approval-1-callback");
        runner.completeCallback(callbackId, "\"approved\"");

        result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("prepared -> approved -> done", result.getResult(String.class));
    }

    @Test
    void waitForCallbackRetryMultipleFailuresThenSuccess() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) ->
                                ctx.waitForCallback("cb-" + attempt, String.class, (callbackId, stepCtx) -> {}),
                        WithRetryConfig.builder()
                                .retryStrategy(RetryStrategies.fixedDelay(4, Duration.ofSeconds(1)))
                                .build()));

        // Attempt 1 — fail
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        runner.failCallback(
                runner.getCallbackId("cb-1-callback"),
                ErrorObject.builder().errorMessage("fail 1").build());
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        runner.advanceTime();
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Attempt 2 — fail
        runner.failCallback(
                runner.getCallbackId("cb-2-callback"),
                ErrorObject.builder().errorMessage("fail 2").build());
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        runner.advanceTime();
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Attempt 3 — succeed
        runner.completeCallback(runner.getCallbackId("cb-3-callback"), "\"third time's the charm\"");
        result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("third time's the charm", result.getResult(String.class));
    }

    @Test
    void waitForCallbackRetryWithSubmitterLogic() {
        // Verify the submitter runs on each retry attempt
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) ->
                                ctx.waitForCallback("approval-" + attempt, String.class, (callbackId, stepCtx) -> {
                                    // Submitter runs each attempt — in a real scenario this would
                                    // send the callbackId to an external system
                                    stepCtx.getLogger().info("Attempt {} submitting {}", attempt, callbackId);
                                }),
                        WithRetryConfig.builder()
                                .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(2)))
                                .build()));

        // Attempt 1
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Verify submitter step was created for attempt 1
        var submitterOp = runner.getOperation("approval-1-submitter");
        assertNotNull(submitterOp, "Submitter step should exist for attempt 1");

        // Fail attempt 1
        runner.failCallback(
                runner.getCallbackId("approval-1-callback"),
                ErrorObject.builder().errorMessage("rejected").build());
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff, start attempt 2
        runner.advanceTime();
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Verify submitter step was created for attempt 2
        var submitterOp2 = runner.getOperation("approval-2-submitter");
        assertNotNull(submitterOp2, "Submitter step should exist for attempt 2");

        // Complete attempt 2
        runner.completeCallback(runner.getCallbackId("approval-2-callback"), "\"approved\"");
        result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("approved", result.getResult(String.class));
    }
}
