// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.lambda.durable.DurableCoreOperations.step;
import static software.amazon.lambda.durable.extension.PairOperations.pairAsync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ExtensionOperationIntegrationTest {
    @Test
    void reservedOperationsReplayWhenLaunchOrderChanges() {
        var extensionExecutions = new AtomicInteger();
        var runner =
                LocalDurableTestRunner.create(String.class, (input, context) -> pairAsync("pair", extensionExecutions)
                        .get());

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("LR", result.getResult(String.class));
        assertTrue(extensionExecutions.get() >= 2);

        assertEquals(hash("1"), result.getOperation("pair-left").getId());
        assertEquals(hash("2"), result.getOperation("pair-right").getId());
        assertEquals(hash("3"), result.getOperation("pair-pause").getId());
    }

    @Test
    void customReservationsRemainStableWhenRegistrationOrderChanges() {
        var invocations = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var extension = ExtensionContext.getCurrentContext();
            var replay = invocations.incrementAndGet() > 1;
            var first = replay ? extension.reserve("right", "right") : extension.reserve("left", "left");
            var second = replay ? extension.reserve("left", "left") : extension.reserve("right", "right");
            first.step(String.class, () -> first == second ? "invalid" : "first");
            second.step(String.class, () -> "second");
            context.wait("replay", Duration.ofSeconds(1));
            return "done";
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(hash("left"), result.getOperation("left").getId());
        assertEquals(hash("right"), result.getOperation("right").getId());
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
        assertThrows(IllegalStateException.class, DurableContext::getCurrentContext);
    }

    @Test
    void extensionCanExplicitlyCreateChildContext() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var outer = ExtensionContext.getCurrentContext();
            return outer.reserve("child").runInChildContext(String.class, () -> {
                var child = ExtensionContext.getCurrentContext();
                assertNotSame(outer, child);
                return child.reserve("value", "node").step(String.class, () -> "nested");
            });
        });

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("nested", result.getResult(String.class));
        assertEquals(hash("1"), result.getOperation("child").getId());
        assertEquals(hash(hash("1") + "-node"), result.getOperation("value").getId());
    }

    @Test
    void customPrimitiveSubtypesAreStoredWithoutChangingOperationTypes() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var extension = ExtensionContext.getCurrentContext();
            extension.reserve("custom-step").step("AcmeStep", String.class, () -> "step");
            extension.reserve("custom-wait").wait("AcmeWait", Duration.ofSeconds(1));
            return extension.reserve("custom-context").runInChildContext("AcmeContext", String.class, () -> "done");
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
        var runner =
                LocalDurableTestRunner.create(Integer.class, (input, context) -> ExtensionContext.getCurrentContext()
                        .reserve("stateful")
                        .step(
                                "AcmeStateful",
                                Integer.class,
                                state -> state >= 2
                                        ? ExtensionStepResult.succeed(state)
                                        : ExtensionStepResult.retry(state + 1, Duration.ofSeconds(1)),
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
    void extensionContextExposesStoredReplayStateWhileReplayingChildren() {
        var replayState = new AtomicReference<String>();
        var executions = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = ExtensionContext.getCurrentContext()
                    .reserve("advanced")
                    .runInChildContext(
                            "AcmeContext",
                            String.class,
                            () -> {
                                executions.incrementAndGet();
                                var replay = ExtensionContextReplayContext.<String>getCurrentContext();
                                if (replay.isReplayingChildren()) {
                                    replayState.set(replay.getReplayState());
                                }
                                return ExtensionContextResult.replayChildren("full", "stored");
                            },
                            ExtensionContextConfig.builder().build());
            context.wait("replay", Duration.ofSeconds(1));
            return result;
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
