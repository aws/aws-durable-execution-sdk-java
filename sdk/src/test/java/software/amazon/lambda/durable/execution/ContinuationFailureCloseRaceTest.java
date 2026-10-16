// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

class ContinuationFailureCloseRaceTest {
    @ParameterizedTest
    @CsvSource({"body,true", "reject,true", "body,false", "reject,false"})
    void failureSelectionIsOrderedWithCloseAndDoesNotRunCallbacksUnderCoordinationLock(String mode, boolean closeFirst)
            throws Exception {
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/close-race/execution",
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
        var callbacks = new CountDownLatch(1);
        var callback = manager.runUntilCompleteOrSuspend(new CompletableFuture<String>())
                .handle((value, error) -> {
                    try {
                        CompletableFuture.runAsync(() -> manager.registerActiveThread("callback-lock-proof"))
                                .get(3, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError("Failure callbacks must not hold the activeThreads monitor", failure);
                    }
                    callbacks.countDown();
                    return error;
                });
        var owner = mock(BaseDurableOperation.class);
        var ownerCompletion = new CompletableFuture<BaseDurableOperation>();
        when(owner.getCompletionFuture()).thenReturn(ownerCompletion);
        var unrelated = mock(BaseDurableOperation.class);
        var unrelatedCompletion = new CompletableFuture<BaseDurableOperation>();
        when(unrelated.getOperationId()).thenReturn("unrelated");
        when(unrelated.getCompletionFuture()).thenReturn(unrelatedCompletion);
        manager.registerOperation(unrelated);
        var buildingFailure = new CountDownLatch(1);
        var releaseFailure = new CountDownLatch(1);
        RuntimeException failure = mode.equals("reject")
                ? new RejectedExecutionException("coordinator rejected")
                : new IllegalArgumentException("continuation failed");
        var publisher = CompletableFuture.runAsync(() -> {
            // A scheduling gate inside ordinary control construction, after the old caller's isClosing precheck.
            // It does not replace manager state, futures, close, or stopAllOperations.
            try (var model = mockStatic(ErrorObject.class, CALLS_REAL_METHODS)) {
                model.when(ErrorObject::builder).thenAnswer(call -> {
                    buildingFailure.countDown();
                    assertTrue(releaseFailure.await(3, TimeUnit.SECONDS));
                    return call.callRealMethod();
                });
                if (mode.equals("reject")) {
                    assertSame(
                            failure,
                            assertThrows(
                                    RejectedExecutionException.class,
                                    () -> manager.runCheckpointContinuation(
                                            owner, () -> fail("Rejected work"), task -> {
                                                throw failure;
                                            })));
                } else {
                    var observed = manager.runCheckpointContinuation(
                            owner,
                            () -> {
                                throw failure;
                            },
                            Runnable::run);
                    assertSame(
                            failure,
                            assertThrows(CompletionException.class, observed::join)
                                    .getCause());
                }
            }
        });
        CompletableFuture<Void> closing = null;
        try {
            assertTrue(buildingFailure.await(3, TimeUnit.SECONDS));
            if (closeFirst) {
                closing = CompletableFuture.runAsync(manager::close);
                assertInstanceOf(
                        SuspendExecutionException.class,
                        assertThrows(ExecutionException.class, () -> ownerCompletion.get(3, TimeUnit.SECONDS))
                                .getCause());
                assertFalse(unrelatedCompletion.isDone());
                var pendingClose = closing;
                assertThrows(TimeoutException.class, () -> pendingClose.get(100, TimeUnit.MILLISECONDS));
            }
            releaseFailure.countDown();
            publisher.get(3, TimeUnit.SECONDS);
            if (closing == null) closing = CompletableFuture.runAsync(manager::close);
            closing.get(3, TimeUnit.SECONDS);
            System.out.println("CONTINUATION_CLOSE_RACE mode=" + mode + " closeFirst=" + closeFirst
                    + " unrelatedStopped=" + unrelatedCompletion.isDone()
                    + " managerFailure=" + manager.isExecutionCompletedExceptionally());
            assertEquals(
                    !closeFirst,
                    unrelatedCompletion.isDone(),
                    "Only failure selected before close may stop unrelated operations");
            assertEquals(!closeFirst, manager.isExecutionCompletedExceptionally());
            if (!closeFirst) {
                assertNotNull(callback.get(3, TimeUnit.SECONDS));
                assertEquals(0, callbacks.getCount());
            } else assertEquals(1, callbacks.getCount());
            assertDoesNotThrow(() -> manager.deregisterActiveThread("root"));
        } finally {
            releaseFailure.countDown();
            publisher.get(3, TimeUnit.SECONDS);
            if (closing == null) closing = CompletableFuture.runAsync(manager::close);
            closing.get(3, TimeUnit.SECONDS);
        }
    }
}
