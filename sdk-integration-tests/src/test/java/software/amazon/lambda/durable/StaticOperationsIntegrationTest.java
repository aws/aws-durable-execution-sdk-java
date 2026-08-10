// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class StaticOperationsIntegrationTest {
    @Test
    void coreOperationsExposeStepAndChildContextsThroughTls() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var root = ExtensionContext.getCurrentContext();
            var step = DurableCoreOperations.step(
                    "step",
                    String.class,
                    () -> "attempt-" + StepContext.getCurrentContext().getAttempt());
            var child = DurableCoreOperations.runInChildContext("child", String.class, () -> {
                assertNotSame(root, ExtensionContext.getCurrentContext());
                return DurableCoreOperations.step("child-step", String.class, () -> "child");
            });
            return step + ":" + child;
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("attempt-1:child", result.getResult(String.class));
    }

    @Test
    void mapAndParallelExposeContextFreeUserFunctions() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var mapResult = DurableMapOperations.map("map", List.of("a", "b"), String.class, item -> {
                var index = MapItemContext.getCurrentContext().getIndex();
                return DurableCoreOperations.step("map-step", String.class, () -> item + index);
            });

            var branchFutures = new ArrayList<DurableFuture<String>>();
            try (var parallel = DurableParallelOperations.parallel("parallel")) {
                branchFutures.add(parallel.branch(
                        "left",
                        String.class,
                        () -> DurableCoreOperations.step("branch-step", String.class, () -> "L")));
                branchFutures.add(parallel.branch(
                        "right",
                        String.class,
                        () -> DurableCoreOperations.step("branch-step", String.class, () -> "R")));
            }
            return mapResult.results() + ":" + DurableFuture.allOf(branchFutures);
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("[a0, b1]:[L, R]", result.getResult(String.class));
    }

    @Test
    void conditionAndRetryExposeGeneratedMetadataThroughTls() {
        var retryExecutions = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var condition = DurableWaitForConditionOperations.waitForCondition(
                    "condition",
                    Integer.class,
                    state -> {
                        assertNotNull(StepContext.getCurrentContext());
                        return WaitForConditionResult.stopPolling(state + 1);
                    },
                    WaitForConditionConfig.<Integer>builder().initialState(0).build());
            var retry = DurableWithRetryOperations.withRetry("retry", () -> {
                var attempt = WithRetryContext.getCurrentContext().getAttempt();
                retryExecutions.incrementAndGet();
                if (attempt == 1) {
                    throw new IllegalStateException("retry");
                }
                return attempt;
            });
            return condition + ":" + retry;
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("1:2", result.getResult(String.class));
        assertTrue(retryExecutions.get() >= 2);
    }

    @Test
    void waitForCallbackExposesCallbackIdThroughTls() {
        var submittedId = new AtomicReference<String>();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> DurableWaitForCallbackOperations.waitForCallback(
                        "approval",
                        String.class,
                        () -> submittedId.set(
                                WaitForCallbackContext.getCurrentContext().getCallbackId())));

        var pending = runner.run("input");

        assertEquals(ExecutionStatus.PENDING, pending.getStatus());
        var callbackId = runner.getCallbackId("approval-callback");
        assertEquals(callbackId, submittedId.get());
        runner.completeCallback(callbackId, "\"approved\"");

        var completed = runner.run("input");

        assertEquals(ExecutionStatus.SUCCEEDED, completed.getStatus());
        assertEquals("approved", completed.getResult(String.class));
    }
}
