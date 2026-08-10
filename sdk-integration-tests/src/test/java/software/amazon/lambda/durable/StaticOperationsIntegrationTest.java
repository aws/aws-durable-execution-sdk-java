// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.operation.DurableContextOperation;
import software.amazon.lambda.durable.operation.DurableMapOperation;
import software.amazon.lambda.durable.operation.DurableMapOperation.MapItemContext;
import software.amazon.lambda.durable.operation.DurableParallelOperation;
import software.amazon.lambda.durable.operation.DurableStepOperation;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation.WaitForCallbackContext;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation.WithRetryContext;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestResult;

class StaticOperationsIntegrationTest {
    @Test
    void coreOperationsExposeStepAndChildContextsThroughTls() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var root = ExtensionContext.getCurrentContext();
            var step = DurableStepOperation.step(
                    "step",
                    String.class,
                    () -> "attempt-" + StepContext.getCurrentContext().getAttempt());
            var child = DurableContextOperation.runInChildContext("child", String.class, () -> {
                assertNotSame(root, ExtensionContext.getCurrentContext());
                return DurableStepOperation.step("child-step", String.class, () -> "child");
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
            var mapResult = DurableMapOperation.map("map", List.of("a", "b"), String.class, item -> {
                var index = MapItemContext.getCurrentContext().getIndex();
                return DurableStepOperation.step("map-step", String.class, () -> item + index);
            });

            var branchFutures = new ArrayList<DurableFuture<String>>();
            try (var parallel = DurableParallelOperation.parallel("parallel")) {
                branchFutures.add(parallel.branch(
                        "left",
                        String.class,
                        ignored -> DurableStepOperation.step("branch-step", String.class, () -> "L")));
                branchFutures.add(parallel.branch(
                        "right",
                        String.class,
                        ignored -> DurableStepOperation.step("branch-step", String.class, () -> "R")));
            }
            return mapResult.results() + ":" + DurableFuture.allOf(branchFutures);
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("[a0, b1]:[L, R]", result.getResult(String.class));
    }

    @Test
    void staticMapMatchesLegacyCheckpointHistory() {
        var mapConfig = MapConfig.builder()
                .maxConcurrency(1)
                .nestingType(NestingType.NESTED)
                .build();
        var legacyRunner = LocalDurableTestRunner.create(String.class, (input, context) -> context.map(
                        "map",
                        List.of("a", "b"),
                        String.class,
                        (item, index, child) -> child.step("work", String.class, step -> item + index),
                        mapConfig)
                .results()
                .toString());
        var staticRunner = LocalDurableTestRunner.create(String.class, (input, context) -> DurableMapOperation.map(
                        "map",
                        List.of("a", "b"),
                        String.class,
                        item -> {
                            var index = MapItemContext.getCurrentContext().getIndex();
                            return DurableStepOperation.step("work", String.class, () -> item + index);
                        },
                        mapConfig.toOperationConfig())
                .results()
                .toString());

        var legacyResult = legacyRunner.runUntilComplete("input");
        var staticResult = staticRunner.runUntilComplete("input");

        assertEquals("[a0, b1]", legacyResult.getResult(String.class));
        assertEquals(legacyResult.getResult(String.class), staticResult.getResult(String.class));
        assertEquals(operationHistory(legacyResult), operationHistory(staticResult));
    }

    @Test
    void staticParallelMatchesLegacyCheckpointHistory() {
        var parallelConfig = ParallelConfig.builder()
                .maxConcurrency(1)
                .nestingType(NestingType.NESTED)
                .build();
        var legacyRunner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            try (var parallel = context.parallel("parallel", parallelConfig)) {
                parallel.branch("left", String.class, child -> child.step("work", String.class, step -> "L"));
                parallel.branch("right", String.class, child -> child.step("work", String.class, step -> "R"));
                return parallel.get().statuses().toString();
            }
        });
        var staticRunner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            try (var parallel = DurableParallelOperation.parallel("parallel", parallelConfig.toOperationConfig())) {
                parallel.branch(
                        "left", String.class, ignored -> DurableStepOperation.step("work", String.class, () -> "L"));
                parallel.branch(
                        "right", String.class, ignored -> DurableStepOperation.step("work", String.class, () -> "R"));
                return parallel.get().statuses().toString();
            }
        });

        var legacyResult = legacyRunner.runUntilComplete("input");
        var staticResult = staticRunner.runUntilComplete("input");

        assertEquals("[SUCCEEDED, SUCCEEDED]", legacyResult.getResult(String.class));
        assertEquals(legacyResult.getResult(String.class), staticResult.getResult(String.class));
        assertEquals(operationHistory(legacyResult), operationHistory(staticResult));
    }

    @Test
    void staticWaitForCallbackMatchesLegacyCheckpointHistory() {
        var legacyRunner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.waitForCallback("approval", String.class, (callbackId, stepContext) -> {}));
        var staticRunner = LocalDurableTestRunner.create(
                String.class,
                (input, context) ->
                        DurableWaitForCallbackOperation.waitForCallback("approval", String.class, () -> {}));

        var legacyPending = legacyRunner.run("input");
        var staticPending = staticRunner.run("input");
        assertEquals(ExecutionStatus.PENDING, legacyPending.getStatus());
        assertEquals(ExecutionStatus.PENDING, staticPending.getStatus());
        legacyRunner.completeCallback(legacyRunner.getCallbackId("approval-callback"), "\"approved\"");
        staticRunner.completeCallback(staticRunner.getCallbackId("approval-callback"), "\"approved\"");

        var legacyResult = legacyRunner.runUntilComplete("input");
        var staticResult = staticRunner.runUntilComplete("input");

        assertEquals("approved", legacyResult.getResult(String.class));
        assertEquals(legacyResult.getResult(String.class), staticResult.getResult(String.class));
        assertEquals(operationHistory(legacyResult), operationHistory(staticResult));
    }

    @Test
    void staticWaitForConditionMatchesLegacyCheckpointHistory() {
        var config = WaitForConditionConfig.<Integer>builder()
                .initialState(0)
                .waitStrategy(WaitStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                .build();
        var legacyRunner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.waitForCondition(
                        "condition", Integer.class, (state, stepContext) -> nextConditionState(state), config));
        var staticRunner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> DurableWaitForConditionOperation.waitForCondition(
                        "condition",
                        Integer.class,
                        StaticOperationsIntegrationTest::nextOperationConditionState,
                        config.toOperationConfig()));

        var legacyResult = legacyRunner.runUntilComplete("input");
        var staticResult = staticRunner.runUntilComplete("input");

        assertEquals(2, legacyResult.getResult(Integer.class));
        assertEquals(legacyResult.getResult(Integer.class), staticResult.getResult(Integer.class));
        assertEquals(operationHistory(legacyResult), operationHistory(staticResult));
    }

    @Test
    void staticWithRetryMatchesLegacyCheckpointHistory() {
        var config = WithRetryConfig.builder()
                .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                .wrapInChildContext(true)
                .build();
        var legacyRunner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.withRetry(
                        "retry",
                        (attempt, child) -> retryAttempt(
                                attempt, () -> child.step("work", String.class, stepContext -> "attempt-" + attempt)),
                        config));
        var staticRunner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> DurableWithRetryOperation.withRetry(
                        "retry",
                        () -> {
                            var attempt = WithRetryContext.getCurrentContext().getAttempt();
                            return retryAttempt(
                                    attempt,
                                    () -> DurableStepOperation.step("work", String.class, () -> "attempt-" + attempt));
                        },
                        config.toOperationConfig()));

        var legacyResult = legacyRunner.runUntilComplete("input");
        var staticResult = staticRunner.runUntilComplete("input");

        assertEquals("attempt-2", legacyResult.getResult(String.class));
        assertEquals(legacyResult.getResult(String.class), staticResult.getResult(String.class));
        assertEquals(operationHistory(legacyResult), operationHistory(staticResult));
    }

    @Test
    void conditionAndRetryExposeGeneratedMetadataThroughTls() {
        var retryExecutions = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var condition = DurableWaitForConditionOperation.waitForCondition(
                    "condition",
                    Integer.class,
                    state -> {
                        assertNotNull(StepContext.getCurrentContext());
                        return DurableWaitForConditionOperation.WaitForConditionResult.stopPolling(state + 1);
                    },
                    WaitForConditionConfig.<Integer>builder()
                            .initialState(0)
                            .build()
                            .toOperationConfig());
            var retry = DurableWithRetryOperation.withRetry("retry", () -> {
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
                (input, context) -> DurableWaitForCallbackOperation.waitForCallback(
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

    private static WaitForConditionResult<Integer> nextConditionState(int state) {
        var next = state + 1;
        return next >= 2 ? WaitForConditionResult.stopPolling(next) : WaitForConditionResult.continuePolling(next);
    }

    private static DurableWaitForConditionOperation.WaitForConditionResult<Integer> nextOperationConditionState(
            int state) {
        var next = state + 1;
        return next >= 2
                ? DurableWaitForConditionOperation.WaitForConditionResult.stopPolling(next)
                : DurableWaitForConditionOperation.WaitForConditionResult.continuePolling(next);
    }

    private static String retryAttempt(int attempt, Supplier<String> operation) {
        if (attempt == 1) {
            throw new IllegalStateException("retry");
        }
        return operation.get();
    }

    private static List<OperationShape> operationHistory(TestResult<?> result) {
        return result.getOperations().stream()
                .map(operation -> new OperationShape(
                        operation.getId(),
                        operation.getName(),
                        operation.getType(),
                        operation.getSubtype(),
                        operation.getEvents().get(0).parentId(),
                        operation.getStatus()))
                .toList();
    }

    private record OperationShape(
            String id, String name, OperationType type, String subtype, String parentId, OperationStatus status) {}
}
