// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.lambda.durable.extension.PairOperations.customOperationsAsync;
import static software.amazon.lambda.durable.extension.PairOperations.pairAsync;
import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ExtensionOperationIntegrationTest {
    @Test
    void reservedOperationsReplayWhenLaunchOrderChanges() {
        var extensionExecutions = new AtomicInteger();
        var runner = LocalDurableTestRunner.createAsync(
                String.class, (input, context) -> pairAsync("pair", extensionExecutions));

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("LR", result.getResult(String.class));
        assertTrue(extensionExecutions.get() >= 2);

        assertEquals(hash("1"), result.getOperation("pair-left").getId());
        assertEquals(hash("2"), result.getOperation("pair-right").getId());
        assertEquals(hash("3"), result.getOperation("pair-pause").getId());
    }

    @Test
    void customExtensionFixtureSupportsLocalIdsAndSubtypesAcrossReplay() {
        var extensionExecutions = new AtomicInteger();
        var runner = LocalDurableTestRunner.createAsync(
                String.class, (input, context) -> customOperationsAsync("custom", extensionExecutions));

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("step:context", result.getResult(String.class));
        assertTrue(extensionExecutions.get() >= 2);
        assertEquals(hash("custom-step-id"), result.getOperation("custom-step").getId());
        assertEquals(OperationType.STEP, result.getOperation("custom-step").getType());
        assertEquals("AcmeStep", result.getOperation("custom-step").getSubtype());
        assertEquals(hash("custom-wait-id"), result.getOperation("custom-wait").getId());
        assertEquals(OperationType.WAIT, result.getOperation("custom-wait").getType());
        assertEquals("AcmeWait", result.getOperation("custom-wait").getSubtype());
        assertEquals(
                hash("custom-context-id"), result.getOperation("custom-context").getId());
        assertEquals(
                OperationType.CONTEXT, result.getOperation("custom-context").getType());
        assertEquals("AcmeContext", result.getOperation("custom-context").getSubtype());
    }

    @Test
    void staticOperationsUseCurrentContextAndRejectStepThreads() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            assertSame(context, DurableContext.getCurrentContext());
            assertSame(context, ExtensionContext.getCurrentContext());
            return step("current-context", String.class, () -> {
                var stepContext = StepContext.getCurrentContext();
                assertSame(stepContext, StepContext.getCurrentContext());
                return assertThrows(IllegalStateException.class, DurableContext::getCurrentContext)
                        .getMessage();
            });
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertTrue(result.getResult(String.class).contains("step thread"));
        assertNull(DurableContext.getCurrentContext());
    }

    @Test
    void extensionCanExplicitlyCreateChildContext() {
        var runner = LocalDurableTestRunner.createAsync(String.class, (input, context) -> {
            var outer = ExtensionContext.getCurrentContext();
            return outer.reserve("child")
                    .runInChildContextAsync(
                            OperationSubType.RUN_IN_CHILD_CONTEXT.getValue(),
                            TypeToken.get(String.class),
                            () -> {
                                var child = ExtensionContext.getCurrentContext();
                                assertNotSame(outer, child);
                                return child.reserve("value", "node")
                                        .stepAsync(
                                                OperationSubType.STEP.getValue(),
                                                TypeToken.get(String.class),
                                                state -> CompletableFuture.completedFuture(
                                                        ExtensionStepResult.succeed("nested")),
                                                ExtensionStepConfig.<String>builder()
                                                        .build())
                                        .thenApply(ExtensionContextResult::completed);
                            },
                            ExtensionContextConfig.builder().build());
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("nested", result.getResult(String.class));
        assertEquals(hash("1"), result.getOperation("child").getId());
        assertEquals(hash(hash("1") + "-node"), result.getOperation("value").getId());
    }

    @Test
    void customPrimitiveSubtypesAreStoredWithoutChangingOperationTypes() {
        var runner = LocalDurableTestRunner.createAsync(String.class, (input, context) -> {
            var extension = ExtensionContext.getCurrentContext();
            var step = extension.reserve("custom-step");
            var wait = extension.reserve("custom-wait");
            var childContext = extension.reserve("custom-context");
            var stepResult = step.stepAsync(
                    "AcmeStep",
                    TypeToken.get(String.class),
                    state -> CompletableFuture.completedFuture(ExtensionStepResult.succeed("step")),
                    ExtensionStepConfig.<String>builder().build());
            var waitResult = wait.waitAsync("AcmeWait", Duration.ofSeconds(1));
            var contextResult = childContext.runInChildContextAsync(
                    "AcmeContext",
                    TypeToken.get(String.class),
                    () -> CompletableFuture.completedFuture(ExtensionContextResult.completed("done")),
                    ExtensionContextConfig.builder().build());
            return stepResult.thenCompose(ignored -> waitResult.thenCompose(alsoIgnored -> contextResult));
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(OperationType.STEP, result.getOperation("custom-step").getType());
        assertEquals("AcmeStep", result.getOperation("custom-step").getSubtype());
        assertEquals(OperationType.WAIT, result.getOperation("custom-wait").getType());
        assertEquals("AcmeWait", result.getOperation("custom-wait").getSubtype());
        assertEquals(
                OperationType.CONTEXT, result.getOperation("custom-context").getType());
        assertEquals("AcmeContext", result.getOperation("custom-context").getSubtype());
    }

    @Test
    void statefulExtensionStepCheckpointsStateAcrossRetries() {
        var runner = LocalDurableTestRunner.createAsync(
                Integer.class, (input, context) -> ExtensionContext.getCurrentContext()
                        .reserve("stateful")
                        .stepAsync(
                                "AcmeStateful",
                                TypeToken.get(Integer.class),
                                state -> CompletableFuture.completedFuture(
                                        state >= 2
                                                ? ExtensionStepResult.succeed(state)
                                                : ExtensionStepResult.retry(state + 1, Duration.ofSeconds(1))),
                                ExtensionStepConfig.<Integer>builder()
                                        .initialState(0)
                                        .build()));

        var result = runner.runUntilComplete(0);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, result.getResult(Integer.class));
        assertEquals("AcmeStateful", result.getOperation("stateful").getSubtype());
        assertEquals(3, result.getOperation("stateful").getAttempt());
    }

    @Test
    void extensionStepRetriesExceptionsWithExtensionOwnedStrategy() {
        var attempts = new AtomicInteger();
        var failedState = new AtomicReference<String>();
        var resumedState = new AtomicReference<String>();
        var runner = LocalDurableTestRunner.createAsync(
                String.class, (input, context) -> ExtensionContext.getCurrentContext()
                        .reserve("retry")
                        .stepAsync(
                                "AcmeRetry",
                                TypeToken.get(String.class),
                                state -> {
                                    if (attempts.incrementAndGet() == 1) {
                                        throw new IllegalStateException("retry");
                                    }
                                    resumedState.set(state);
                                    return CompletableFuture.completedFuture(ExtensionStepResult.succeed("done"));
                                },
                                ExtensionStepConfig.<String>builder()
                                        .initialState("initial")
                                        .retryStrategy((error, state, attempt) -> {
                                            failedState.set(state);
                                            return attempt < 2
                                                    ? ExtensionStepResult.retry("retried", Duration.ofSeconds(1))
                                                    : ExtensionStepResult.doNotRetry();
                                        })
                                        .build()));

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("done", result.getResult(String.class));
        assertEquals(2, attempts.get());
        assertEquals(2, result.getOperation("retry").getAttempt());
        assertEquals("initial", failedState.get());
        assertEquals("retried", resumedState.get());
    }

    @Test
    void extensionContextExposesStoredReplayStateWhileReplayingChildren() {
        var replayState = new AtomicReference<String>();
        var executions = new AtomicInteger();
        var runner = LocalDurableTestRunner.createAsync(String.class, (input, context) -> {
            var extension = ExtensionContext.getCurrentContext();
            var result = extension
                    .reserve("advanced")
                    .runInChildContextAsync(
                            "AcmeContext",
                            TypeToken.get(String.class),
                            () -> {
                                executions.incrementAndGet();
                                var replay = ExtensionContextReplayContext.<String>getCurrentContext();
                                if (replay.isReplayingChildren()) {
                                    replayState.set(replay.getReplayState());
                                }
                                return CompletableFuture.completedFuture(
                                        ExtensionContextResult.replayChildren("full", "stored"));
                            },
                            ExtensionContextConfig.builder().build());
            var replay = extension.reserve("replay").waitAsync(OperationSubType.WAIT.getValue(), Duration.ofSeconds(1));
            return result.thenCompose(value -> replay.thenApply(ignored -> value));
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("full", result.getResult(String.class));
        assertTrue(executions.get() >= 2);
        assertEquals("stored", replayState.get());
        assertThrows(IllegalStateException.class, ExtensionContextReplayContext::getCurrentContext);
    }

    private static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
