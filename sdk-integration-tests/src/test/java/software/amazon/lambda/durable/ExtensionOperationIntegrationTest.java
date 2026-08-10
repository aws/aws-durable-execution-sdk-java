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
import org.junit.jupiter.api.Test;
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
            var first = replay
                    ? extension.reserve("right", "right")
                    : extension.reserve("left", "left");
            var second = replay
                    ? extension.reserve("left", "left")
                    : extension.reserve("right", "right");
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

    private static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
