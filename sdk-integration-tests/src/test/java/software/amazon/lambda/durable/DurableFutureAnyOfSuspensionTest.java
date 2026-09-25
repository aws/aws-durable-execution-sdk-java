// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * Reproduces GitHub issue #707: {@code DurableFuture.anyOf} joins the raw completion futures instead of routing through
 * {@code BaseDurableOperation.waitForOperationCompletion()}, so the calling thread is never deregistered and the
 * execution can never suspend.
 *
 * <p>The two control cases wait on the same unresolved callbacks via {@code get()} and {@code allOf} and both suspend.
 */
class DurableFutureAnyOfSuspensionTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private static WaitForCallbackConfig longTimeout() {
        return WaitForCallbackConfig.builder()
                .callbackConfig(
                        CallbackConfig.builder().timeout(Duration.ofMinutes(30)).build())
                .build();
    }

    private static DurableFuture<String> unresolvedCallback(DurableContext context, String name) {
        return context.waitForCallbackAsync(
                name,
                String.class,
                (callbackId, stepCtx) -> stepCtx.getLogger().info("Submitted callback {} for {}", callbackId, name),
                longTimeout());
    }

    @Test
    void singleFutureGetSuspends() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> unresolvedCallback(context, "cb1")
                .get());

        var result = assertTimeoutPreemptively(DEADLINE, () -> runner.run("test"));

        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }

    @Test
    void allOfSuspends() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var f1 = unresolvedCallback(context, "cb1");
            var f2 = unresolvedCallback(context, "cb2");
            return String.join(",", DurableFuture.allOf(f1, f2));
        });

        var result = assertTimeoutPreemptively(DEADLINE, () -> runner.run("test"));

        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }

    @Test
    void anyOfSuspends() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var f1 = unresolvedCallback(context, "cb1");
            var f2 = unresolvedCallback(context, "cb2");
            return String.valueOf(DurableFuture.anyOf(f1, f2));
        });

        var result = assertTimeoutPreemptively(DEADLINE, () -> runner.run("test"));

        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }
}
