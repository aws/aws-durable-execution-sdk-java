// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.util.ExceptionHelper;

class ContinuationFatalBudgetTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(10)
    void admittedContinuationMustSettleBeforeFatalClose(boolean reportWhileClosing) throws Exception {
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/budget/execution",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("execution")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .build())
                        .build());
        var manager = new ExecutionManager(
                input,
                DurableConfig.builder()
                        .withDurableExecutionClient(TestUtils.createMockClient())
                        .build(),
                null);
        manager.registerActiveThread("root");
        var worker = Executors.newSingleThreadExecutor();
        var caller = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fatal = new InternalError("simulated invocation fatal");
        var selected = manager.runUntilCompleteOrSuspend(new CompletableFuture<String>());
        var observed = manager.runCheckpointContinuation(
                () -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                },
                worker);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            if (!reportWhileClosing) manager.recordInvocationFatal(fatal);
            var closing = caller.submit(() -> {
                try {
                    manager.close();
                    return (Throwable) null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            if (reportWhileClosing) {
                assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
                manager.recordInvocationFatal(fatal);
            }
            assertThrows(TimeoutException.class, () -> closing.get(700, TimeUnit.MILLISECONDS));
            assertFalse(observed.isDone(), "The actual continuation is still owned while held");
            release.countDown();
            var closeFailure = closing.get(2, TimeUnit.SECONDS);
            if (closeFailure != null) assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(closeFailure));
            assertSame(
                    fatal, ExceptionHelper.unwrapAsyncFailure(assertThrows(CompletionException.class, selected::join)));
            assertTrue(observed.isDone(), "Close must await actual continuation settlement");
            release.countDown();
            observed.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            worker.shutdownNow();
            caller.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
