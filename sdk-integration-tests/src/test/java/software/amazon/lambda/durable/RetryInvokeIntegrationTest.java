// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.WithRetryHelper;

class RetryInvokeIntegrationTest {

    @Test
    void invokeSucceedsOnFirstAttempt() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) -> ctx.invoke("invoke-" + attempt, "target-fn", "{}", String.class),
                        WithRetryConfig.builder()
                                .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(2)))
                                .build()));

        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        runner.completeChainedInvoke("invoke-1", "\"success\"");
        result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("success", result.getResult(String.class));
    }

    @Test
    void invokeRetriesAfterFailure() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) -> ctx.invoke("invoke-" + attempt, "target-fn", "{}", String.class),
                        WithRetryConfig.builder()
                                .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(2)))
                                .build()));

        // First run — invoke attempt 1 starts
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Fail attempt 1
        runner.failChainedInvoke(
                "invoke-1",
                ErrorObject.builder()
                        .errorType("TransientError")
                        .errorMessage("service unavailable")
                        .build());
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff wait
        runner.advanceTime();
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete attempt 2
        runner.completeChainedInvoke("invoke-2", "\"recovered\"");
        result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("recovered", result.getResult(String.class));
    }

    @Test
    void invokeFailsAfterAllRetriesExhausted() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) -> ctx.invoke("invoke-" + attempt, "target-fn", "{}", String.class),
                        WithRetryConfig.builder()
                                .retryStrategy((error, attempt) ->
                                        attempt < 2 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail())
                                .build()));

        // Attempt 1
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        runner.failChainedInvoke(
                "invoke-1", ErrorObject.builder().errorMessage("fail 1").build());
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past backoff
        runner.advanceTime();
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Attempt 2 — last attempt
        runner.failChainedInvoke(
                "invoke-2", ErrorObject.builder().errorMessage("fail 2").build());
        result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void invokeRetryWithCustomBackoffDelay() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) -> ctx.invoke("invoke-" + attempt, "target-fn", "{}", String.class),
                        WithRetryConfig.builder()
                                .retryStrategy((error, attempt) -> attempt < 3
                                        ? RetryDecision.retry(Duration.ofSeconds(attempt * 5L))
                                        : RetryDecision.fail())
                                .build()));

        // Attempt 1 fails
        var result = runner.run("test");
        runner.failChainedInvoke(
                "invoke-1", ErrorObject.builder().errorMessage("fail").build());
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance past first backoff (5s)
        runner.advanceTime();
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Attempt 2 succeeds
        runner.completeChainedInvoke("invoke-2", "\"ok\"");
        result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ok", result.getResult(String.class));
    }

    @Test
    void invokeRetryWithStepsBeforeAndAfter() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var prefix = context.step("prepare", String.class, stepCtx -> "prepared");

            var invokeResult = WithRetryHelper.withRetry(
                    context,
                    (ctx, attempt) -> ctx.invoke("invoke-" + attempt, "target-fn", "{}", String.class),
                    WithRetryConfig.builder()
                            .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                            .build());

            return context.step("finalize", String.class, stepCtx -> prefix + " -> " + invokeResult + " -> done");
        });

        // First run — prepare step completes, invoke starts
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete invoke
        runner.completeChainedInvoke("invoke-1", "\"invoked\"");
        result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("prepared -> invoked -> done", result.getResult(String.class));
    }

    @Test
    void invokeRetryPreservesOriginalExceptionType() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            try {
                return WithRetryHelper.withRetry(
                        context,
                        (ctx, attempt) -> ctx.invoke("invoke-" + attempt, "target-fn", "{}", String.class),
                        WithRetryConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build());
            } catch (InvokeFailedException e) {
                assertEquals("invoke failed", e.getMessage());
                throw e;
            }
        });

        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        runner.failChainedInvoke(
                "invoke-1", ErrorObject.builder().errorMessage("invoke failed").build());
        result = runner.run("test");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }
}
