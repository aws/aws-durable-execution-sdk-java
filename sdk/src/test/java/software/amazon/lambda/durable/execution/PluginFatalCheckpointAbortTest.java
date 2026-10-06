// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.WaitOperation;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(10)
class PluginFatalCheckpointAbortTest {
    @Test
    void externalAbortSettlesDelayedRequestsAndRejectsLaterWork() throws Exception {
        var fatal = new InternalError("external fatal");
        var calls = new AtomicInteger();
        var batcher = new ApiRequestDelayedBatcher<String>(10, 100, String::length, items -> calls.incrementAndGet());
        var pending = batcher.submit("pending", Duration.ofMinutes(1));
        batcher.abortPending(fatal);
        assertSame(
                fatal,
                assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS))
                        .getCause());
        var later = batcher.submit("later", Duration.ofMinutes(1));
        assertSame(
                fatal,
                assertThrows(ExecutionException.class, () -> later.get(1, TimeUnit.SECONDS))
                        .getCause());
        assertSame(
                fatal, ExceptionHelper.unwrapAsyncFailure(assertThrows(CompletionException.class, batcher::shutdown)));
        assertEquals(0, calls.get());
    }

    @Test
    void abortWhileTheWorkerAssemblesABatchPreservesTheFatal() throws Exception {
        var fatal = new InternalError("abort during assembly");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var batcher = new ApiRequestDelayedBatcher<String>(
                10,
                100,
                value -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                    return value.length();
                },
                values -> calls.incrementAndGet());
        var pending = batcher.submit("work", Duration.ZERO);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            batcher.abortPending(fatal);
            assertSame(
                    fatal,
                    assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS))
                            .getCause());
        } finally {
            release.countDown();
        }
        assertSame(
                fatal, ExceptionHelper.unwrapAsyncFailure(assertThrows(CompletionException.class, batcher::shutdown)));
        assertEquals(0, calls.get(), "a drained item must never reach the backend");
    }

    @Test
    void scopeFatalAbortsCheckpointsAndPollersWithoutCompletingRootCleanupEarly() throws Exception {
        var fatal = new InternalError("scope fatal");
        var client = TestUtils.createMockClient();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ofMinutes(1))
                .build();
        var execution = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
        var resumedWait = Operation.builder()
                .id("wait")
                .name("wait")
                .type(OperationType.WAIT)
                .subType(OperationSubType.WAIT.getValue())
                .status(OperationStatus.STARTED)
                .waitDetails(WaitDetails.builder()
                        .scheduledEndTimestamp(Instant.now().plusSeconds(60))
                        .build())
                .build();
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/execution",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(execution, resumedWait)
                        .build());
        var manager = new ExecutionManager(input, config, null);
        var owner = new CompletableFuture<String>();
        var outcome = manager.runUntilCompleteOrSuspend(owner);
        try {
            manager.registerActiveThread(null);
            manager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));
            var context = DurableContextImpl.createRootContext(manager, config, null);
            var wait = new WaitOperation(
                    OperationIdentifier.of("wait", "wait", OperationSubType.WAIT), Duration.ofMinutes(1), context);
            wait.execute();
            var checkpoint = manager.sendOperationUpdate(OperationUpdate.builder()
                    .id("step")
                    .name("step")
                    .type(OperationType.STEP)
                    .action(OperationAction.START)
                    .build());
            var poll = manager.pollForOperationUpdates("remote", Instant.now().plusSeconds(60));
            manager.recordHandlerScopeFatal(fatal);
            assertSame(
                    fatal,
                    assertThrows(ExecutionException.class, () -> checkpoint.get(1, TimeUnit.SECONDS))
                            .getCause());
            assertSame(
                    fatal,
                    assertThrows(ExecutionException.class, () -> poll.get(1, TimeUnit.SECONDS))
                            .getCause());
            assertSame(
                    fatal,
                    ExceptionHelper.unwrapAsyncFailure(assertThrows(
                            CompletionException.class,
                            () -> wait.getCompletionFuture().join())));
            var admitted = manager.tryStartCheckpointProcessing();
            if (admitted) manager.finishCheckpointProcessing();
            assertAll(
                    () -> assertFalse(admitted, "a published fatal must reject backend admission during scope cleanup"),
                    () -> assertDoesNotThrow(() -> manager.deregisterActiveThread(null)),
                    () -> assertFalse(outcome.isDone(), "a fatal must not become a suspension before owner cleanup"));
            assertFalse(outcome.isDone(), "the root must retain ownership of its remaining scope cleanup");
            owner.completeExceptionally(fatal);
            assertSame(
                    fatal, ExceptionHelper.unwrapAsyncFailure(assertThrows(CompletionException.class, outcome::join)));
            verifyNoInteractions(client);
        } finally {
            assertSame(
                    fatal, ExceptionHelper.unwrapAsyncFailure(assertThrows(CompletionException.class, manager::close)));
        }
    }
}
