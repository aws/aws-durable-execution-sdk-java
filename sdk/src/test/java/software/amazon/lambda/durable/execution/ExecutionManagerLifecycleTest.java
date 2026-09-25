// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

class ExecutionManagerLifecycleTest {
    private static final String EXECUTION_ID = "execution";
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/" + EXECUTION_ID;

    @Test
    void capturesRequestIdAndInvocationDeadline() {
        var context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn("request-id");
        when(context.getRemainingTimeInMillis()).thenReturn(2_000);
        var manager = createManager(context, null);

        try {
            assertEquals("request-id", manager.getInvocationId());
            var remaining = manager.getRemainingInvocationTime().orElseThrow();
            assertTrue(remaining.compareTo(Duration.ZERO) > 0);
            assertTrue(remaining.compareTo(Duration.ofSeconds(2)) <= 0);
        } finally {
            manager.close();
        }
    }

    @Test
    void createsDistinctLocalIdsWithoutInventingADeadline() {
        var first = createManager(null, null);
        var second = createManager(null, null);

        try {
            assertNotEquals(first.getInvocationId(), second.getInvocationId());
            assertTrue(first.getRemainingInvocationTime().isEmpty());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void registersTaskBeforeQueuedExecutionAndTracksItsExit() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var blockerEntered = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockerEntered.countDown();
            await(releaseBlocker);
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
        var manager = createManager(null, executor);

        try {
            var completion = manager.submitRootTask(() -> "result");
            var task = manager.getActiveExecutorTasks().get(0);

            assertEquals(ExecutorTaskHandle.State.REGISTERED, task.state());
            assertNotNull(task.execution());
            assertFalse(completion.isDone());
            assertFalse(task.exit().isDone());

            releaseBlocker.countDown();
            assertEquals("result", completion.get(5, TimeUnit.SECONDS));
            task.exit().get(5, TimeUnit.SECONDS);
            assertEquals(ExecutorTaskHandle.State.EXITED, task.state());
            assertTrue(manager.getActiveExecutorTasks().isEmpty());
        } finally {
            releaseBlocker.countDown();
            manager.close();
            stop(executor);
        }
    }

    @Test
    void taskCancellationSeparatesLogicalCompletionFromActualExit() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var manager = createManager(null, executor);
        try {
            var completion = manager.submitRootTask(() -> {
                entered.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                    }
                }
                return "ignored";
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var task = manager.getActiveExecutorTasks().get(0);

            assertTrue(task.cancel(true));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(completion.isCancelled());
            assertFalse(task.exit().isDone());

            release.countDown();
            task.exit().get(5, TimeUnit.SECONDS);
            assertEquals(ExecutorTaskHandle.State.EXITED, task.state());
        } finally {
            release.countDown();
            manager.close();
            stop(executor);
        }
    }

    @Test
    void cancellationRequestedBeforeExecutorHandleIsBoundIsNotLost() throws Exception {
        var exits = new AtomicInteger();
        var task = new ExecutorTaskHandle<String>(1, ExecutorTaskHandle.Role.ROOT, null, exits::incrementAndGet);
        var execution = new FutureTask<Void>(() -> null);

        assertTrue(task.cancel(true));
        assertTrue(task.completion().isCancelled());
        assertFalse(task.exit().isDone());

        task.bindExecution(execution);

        assertTrue(execution.isCancelled());
        task.exit().get(5, TimeUnit.SECONDS);
        assertEquals(1, exits.get());
    }

    @Test
    void failedSubmissionIsRemovedFromManager() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var manager = createManager(null, executor);

        try {
            assertThrows(RejectedExecutionException.class, () -> manager.submitRootTask(() -> "result"));
            assertTrue(manager.getActiveExecutorTasks().isEmpty());
        } finally {
            manager.close();
        }
    }

    @Test
    void drainingRejectsNewWorkButAllowsExistingCheckpointCleanup() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var manager = createManager(null, executor);
        manager.beginDraining();

        try {
            assertEquals(ExecutionManager.LifecycleState.DRAINING, manager.getLifecycleState());
            assertThrows(
                    RejectedExecutionException.class,
                    () -> manager.registerOperation(mock(BaseDurableOperation.class)));
            assertThrows(RejectedExecutionException.class, () -> manager.submitRootTask(() -> "result"));

            var checkpoint = manager.sendOperationUpdate(OperationUpdate.builder()
                    .id("step")
                    .name("step")
                    .type(OperationType.STEP)
                    .subType("Step")
                    .action(OperationAction.START)
                    .build());
            manager.close();
            checkpoint.get(5, TimeUnit.SECONDS);

            assertEquals(ExecutionManager.LifecycleState.CLOSED, manager.getLifecycleState());
            assertThrows(
                    RejectedExecutionException.class,
                    () -> manager.sendOperationUpdate(OperationUpdate.builder().build()));
        } finally {
            stop(executor);
        }
    }

    private ExecutionManager createManager(Context context, ExecutorService executor) {
        var config = DurableConfig.builder().withDurableExecutionClient(TestUtils.createMockClient());
        if (executor != null) {
            config.withExecutorService(executor);
        }
        var execution = Operation.builder()
                .id(EXECUTION_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
        return new ExecutionManager(
                new DurableExecutionInput(
                        EXECUTION_ARN,
                        "token",
                        CheckpointUpdatedExecutionState.builder()
                                .operations(List.of(execution))
                                .build()),
                config.build(),
                context);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test synchronization timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
