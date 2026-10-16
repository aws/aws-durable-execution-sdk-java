// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void cancellationInterruptsCallerRunsBeforeSubmissionReturns() throws Exception {
        var executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        var caller = Executors.newSingleThreadExecutor();
        var workerEntered = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var bodyEntered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var manager = createManager(null, executor);
        executor.execute(() -> {
            workerEntered.countDown();
            await(releaseWorker);
        });
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
        try {
            var submitted = caller.submit(() -> manager.submitRootTask(() -> {
                bodyEntered.countDown();
                while (releaseBody.getCount() > 0) {
                    try {
                        releaseBody.await();
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                    }
                }
                return "ignored";
            }));
            assertTrue(bodyEntered.await(5, TimeUnit.SECONDS));
            assertFalse(submitted.isDone(), "CallerRunsPolicy has not returned from task submission");
            var task = manager.getActiveExecutorTasks().get(0);
            assertNotNull(task.execution(), "The interruptible handle must exist before inline execution starts");
            assertTrue(task.cancel(true));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(task.completion().isCancelled());
            assertFalse(task.exit().isDone(), "Cancellation is not proof that user code has exited");
            releaseBody.countDown();
            task.exit().get(5, TimeUnit.SECONDS);
            assertTrue(submitted.get(5, TimeUnit.SECONDS).isCancelled());
            assertTrue(manager.getActiveExecutorTasks().isEmpty());
        } finally {
            releaseBody.countDown();
            releaseWorker.countDown();
            manager.close();
            stop(caller);
            stop(executor);
        }
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
    void cancellationInterruptsBeforeLogicalObserversWaitForExit() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var cancelling = Executors.newSingleThreadExecutor();
        var manager = createManager(null, executor);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            manager.submitRootTask(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
                return "ignored";
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var task = manager.getActiveExecutorTasks().get(0);
            var observer = task.completion()
                    .whenComplete((result, failure) -> task.exit().join());
            var cancellation = cancelling.submit(() -> task.cancel(true));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "A completion observer must not delay interruption");
            assertTrue(cancellation.get(5, TimeUnit.SECONDS));
            assertTrue(task.completion().isCancelled());
            assertTrue(observer.isDone());
            assertTrue(manager.getActiveExecutorTasks().isEmpty());
        } finally {
            release.countDown();
            stop(executor);
            stop(cancelling);
            manager.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedResultCannotBeOvertakenByCancellationBeforePublication(boolean failAction) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var publishing = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var expectedFailure = new IllegalStateException("selected failure");
        var completion = new CompletableFuture<String>() {
            private void waitBeforePublication() {
                publishing.countDown();
                boolean interrupted = false;
                while (releasePublication.getCount() > 0) {
                    try {
                        releasePublication.await();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }

            @Override
            public boolean complete(String value) {
                waitBeforePublication();
                return super.complete(value);
            }

            @Override
            public boolean completeExceptionally(Throwable failure) {
                waitBeforePublication();
                return super.completeExceptionally(failure);
            }
        };
        var task = new ExecutorTaskHandle<String>(1, ExecutorTaskHandle.Role.ROOT, null, () -> {});
        // Hold publication after the worker selected its outcome, before CompletableFuture runs observers.
        var completionField = ExecutorTaskHandle.class.getDeclaredField("completion");
        completionField.setAccessible(true);
        completionField.set(task, completion);
        var execution = new FutureTask<Void>(
                () -> task.run(() -> {
                    if (failAction) throw expectedFailure;
                    return "selected value";
                }),
                null);
        task.bindExecution(execution);
        try {
            executor.execute(execution);
            assertTrue(publishing.await(5, TimeUnit.SECONDS));
            assertFalse(task.cancel(true), "A selected result must win before its observers are published");
            assertFalse(execution.isCancelled());
            assertFalse(completion.isDone());
            assertFalse(task.exit().isDone(), "Outcome selection is not actual wrapper exit");
            releasePublication.countDown();
            if (failAction) {
                var failure = assertThrows(ExecutionException.class, () -> completion.get(5, TimeUnit.SECONDS));
                assertSame(expectedFailure, failure.getCause());
            } else {
                assertEquals("selected value", completion.get(5, TimeUnit.SECONDS));
            }
            task.exit().get(5, TimeUnit.SECONDS);
        } finally {
            releasePublication.countDown();
            stop(executor);
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
