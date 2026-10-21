// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.*;

@Timeout(15)
class InvocationCleanupBoundaryTest {
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/cleanup/execution";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("removal")
    void cleanupBudgetCannotReplaceSelectedJvmFatal(boolean threadDeath) throws Exception {
        var users = Executors.newSingleThreadExecutor();
        var manager = manager(users, context(2_000));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("selected fatal");
        try {
            manager.submitRootTask(() -> {
                entered.countDown();
                awaitIgnoringInterrupt(release);
                return "ignored";
            });
            await(entered);
            var task = manager.getActiveExecutorTasks().get(0);
            assertSame(fatal, manager.finishInvocation(fatal));
            assertFalse(task.exit().isDone(), "The fixture deliberately retains an uncooperative owner");
            assertTrue(Arrays.stream(fatal.getSuppressed()).anyMatch(ExecutionManager::isLifecycleFailure));
            assertEquals(ExecutionManager.LifecycleState.CLOSED, manager.getLifecycleState());
        } finally {
            release.countDown();
            stop(users);
            manager.close();
        }
    }

    @Test
    void interruptedCallerStillObservesCooperativeExitAndSettlesCheckpoints() throws Exception {
        var users = Executors.newSingleThreadExecutor();
        var callers = Executors.newSingleThreadExecutor();
        var manager = manager(users, null);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var releaseExit = new CountDownLatch(1);
        try {
            manager.submitRootTask(() -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                } finally {
                    awaitIgnoringInterrupt(releaseExit);
                }
                return "cancelled";
            });
            await(entered);
            var task = manager.getActiveExecutorTasks().get(0);
            var checkpoint = manager.sendOperationUpdate(OperationUpdate.builder()
                    .id("queued")
                    .type(OperationType.STEP)
                    .action(OperationAction.START)
                    .build());
            var finished = callers.submit(() -> {
                Thread.currentThread().interrupt();
                var failure = manager.finishInvocation(null);
                assertTrue(Thread.interrupted(), "Cleanup restores the invocation caller's interrupt");
                return failure;
            });
            await(interrupted);
            assertFalse(finished.isDone(), "Interruption cannot skip the cooperative owner's actual exit");
            assertFalse(task.exit().isDone());
            releaseExit.countDown();
            var failure =
                    assertInstanceOf(UnrecoverableDurableExecutionException.class, finished.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(task.exit().isDone());
            assertTrue(checkpoint.isDone(), "Checkpoint shutdown still settles queued waiters");
        } finally {
            releaseExit.countDown();
            stop(users);
            stop(callers);
            manager.close();
        }
    }

    @Test
    void recordedDeadlineFailureSurvivesCleanupWithoutAnOriginalFailure() {
        var users = Executors.newSingleThreadExecutor();
        var manager = manager(users, context(700));
        try {
            var recorded = manager.awaitInvocationOutcome(new CompletableFuture<Void>())
                    .failure();
            assertInstanceOf(UnrecoverableDurableExecutionException.class, recorded);
            assertSame(recorded, manager.finishInvocation(null));
        } finally {
            users.shutdownNow();
            manager.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalAcknowledgmentStopsAdditionalMetadataPagesAtCutoff(boolean admitFirstPage) throws Exception {
        for (boolean terminal : List.of(false, true)) {
            var client = mock(DurableExecutionClient.class);
            var expired = new AtomicBoolean();
            var published = new AtomicReference<List<Operation>>();
            var inline = Operation.builder()
                    .id("inline")
                    .type(OperationType.STEP)
                    .status(OperationStatus.SUCCEEDED)
                    .build();
            var paged = Operation.builder()
                    .id("paged")
                    .type(OperationType.STEP)
                    .status(OperationStatus.SUCCEEDED)
                    .build();
            var cutoff = cutoff();
            when(client.checkpoint(any(), any(), any())).thenAnswer(call -> {
                expired.set(!admitFirstPage);
                return CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("accepted-token")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(inline)
                                .nextMarker("first")
                                .build())
                        .build();
            });
            when(client.getExecutionState(any(), any(), any())).thenAnswer(call -> {
                expired.set(true);
                return GetDurableExecutionStateResponse.builder()
                        .operations(paged)
                        .nextMarker("must-not-fetch")
                        .build();
            });
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(client)
                    .withCheckpointDelay(Duration.ZERO)
                    .build();
            var checkpoints =
                    new CheckpointManager(config, ARN, "token", published::set, () -> true, () -> {}, () -> {}, () -> {
                        if (expired.get()) throw cutoff;
                    });
            try {
                var future = checkpoints.checkpoint(OperationUpdate.builder()
                        .id("update")
                        .type(terminal ? OperationType.EXECUTION : OperationType.STEP)
                        .action(OperationAction.SUCCEED)
                        .build());
                if (terminal) {
                    future.get(5, TimeUnit.SECONDS);
                    assertEquals(admitFirstPage ? List.of(inline, paged) : List.of(inline), published.get());
                    assertTrue(checkpoints.isTerminalExecutionAcknowledged());
                } else {
                    assertSame(
                            cutoff,
                            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS))
                                    .getCause());
                    assertNull(published.get());
                }
                verify(client, times(admitFirstPage ? 1 : 0)).getExecutionState(any(), any(), any());
            } finally {
                checkpoints.shutdown(Duration.ofSeconds(1));
            }
        }
    }

    @Test
    void terminalAcknowledgmentDoesNotHideUnrelatedMetadataFailure() throws Exception {
        var client = mock(DurableExecutionClient.class);
        var problem = new IllegalStateException("metadata store failed");
        when(client.checkpoint(any(), any(), any()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("accepted-token")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .nextMarker("first")
                                .build())
                        .build());
        when(client.getExecutionState(any(), any(), any())).thenThrow(problem);
        var checkpoints = new CheckpointManager(
                DurableConfig.builder()
                        .withDurableExecutionClient(client)
                        .withCheckpointDelay(Duration.ZERO)
                        .build(),
                ARN,
                "token",
                ignored -> {});
        try {
            var future = checkpoints.checkpoint(OperationUpdate.builder()
                    .id("execution")
                    .type(OperationType.EXECUTION)
                    .action(OperationAction.SUCCEED)
                    .build());
            assertSame(
                    problem,
                    assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertTrue(checkpoints.isTerminalExecutionAcknowledged());
        } finally {
            checkpoints.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void lateOwnerEndRestoresScopeBeforeTheSameWorkerIsReused() throws Exception {
        var users = Executors.newSingleThreadExecutor();
        var callers = Executors.newSingleThreadExecutor();
        var scope = new ThreadLocal<String>();
        var prior = new ThreadLocal<String>();
        var owner = new AtomicReference<Thread>();
        var ended = new AtomicInteger();
        var endStatus = new AtomicReference<InvocationStatus>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var end = new CountDownLatch(1);
        var lateBodies = new AtomicInteger();
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                owner.set(Thread.currentThread());
                prior.set(scope.get());
                scope.set("invocation");
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                assertSame(owner.get(), Thread.currentThread());
                assertEquals("invocation", scope.get());
                scope.set(prior.get());
                prior.remove();
                endStatus.set(info.invocationStatus());
                ended.incrementAndGet();
                end.countDown();
            }
        };
        try {
            users.submit(() -> scope.set("host")).get(5, TimeUnit.SECONDS);
            var config = DurableConfig.builder()
                    .withExecutorService(users)
                    .withDurableExecutionClient(TestUtils.createMockClient())
                    .withPlugins(ignoredInvocation -> plugin)
                    .build();
            var invocation = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    context(2_000),
                    TypeToken.get(String.class),
                    (value, ctx) -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        assertThrows(
                                UnrecoverableDurableExecutionException.class,
                                () -> ctx.step("late", String.class, step -> {
                                    lateBodies.incrementAndGet();
                                    return "late";
                                }));
                        return "late-result";
                    },
                    config));
            await(entered);
            assertInstanceOf(
                    UnrecoverableDurableExecutionException.class,
                    assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertEquals(0, ended.get(), "The caller cannot run End on a different thread to force cleanup");
            release.countDown();
            await(end);
            assertEquals(InvocationStatus.RETRYING, endStatus.get());
            assertEquals(1, ended.get());
            assertEquals(0, lateBodies.get());
            assertSame(owner.get(), users.submit(Thread::currentThread).get(5, TimeUnit.SECONDS));
            assertEquals("host", users.submit(scope::get).get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            stop(users);
            stop(callers);
        }
    }

    @Test
    void terminalAckDoesNotHideAvailableFatalDuringTimedCheckpointCleanup() throws Exception {
        var users = Executors.newSingleThreadExecutor();
        var callers = Executors.newSingleThreadExecutor();
        var releaseChange = new CountDownLatch(1);
        var changeEntered = new CountDownLatch(1);
        var fatalDelivered = new CountDownLatch(1);
        var fatal = new InternalError("fatal from End after terminal ACK");
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("accepted")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(Operation.builder()
                                        .id("execution")
                                        .type(OperationType.EXECUTION)
                                        .status(OperationStatus.SUCCEEDED)
                                        .build())
                                .build())
                        .build());
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onOperationChange(OperationChangeInfo info) {
                changeEntered.countDown();
                awaitIgnoringInterrupt(releaseChange);
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                fatalDelivered.countDown();
                throw fatal;
            }
        };
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(users)
                    .withDurableExecutionClient(client)
                    .withPlugins(ignoredInvocation -> plugin)
                    .withCheckpointDelay(Duration.ZERO)
                    .build();
            var invocation = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    context(2_000),
                    TypeToken.get(String.class),
                    (value, ctx) -> "x".repeat(6 * 1024 * 1024 + 100),
                    config));
            await(changeEntered);
            var thrown = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS));
            assertSame(
                    fatal,
                    thrown.getCause(),
                    "An accepted terminal result cannot hide a fatal already delivered by End");
            assertEquals(0, fatalDelivered.getCount());
        } finally {
            releaseChange.countDown();
            stop(users);
            stop(callers);
        }
    }

    @Test
    void finalEmptyTaskSnapshotClosesAdmissionBeforeAQueuedSubmitterCanEnter() throws Exception {
        var users = Executors.newSingleThreadExecutor();
        var callers = Executors.newFixedThreadPool(2);
        var emptySnapshot = new CountDownLatch(1);
        var releaseSnapshot = new CountDownLatch(1);
        var submitted = new CountDownLatch(1);
        var submitter = new AtomicReference<Thread>();
        var intercept = new AtomicBoolean(true);
        var config = DurableConfig.builder()
                .withExecutorService(users)
                .withDurableExecutionClient(TestUtils.createMockClient())
                .build();
        var manager = new ExecutionManager(input(), config, null) {
            @Override
            List<ExecutorTaskHandle<?>> getActiveExecutorTasks() {
                var tasks = super.getActiveExecutorTasks();
                if (tasks.isEmpty() && intercept.compareAndSet(true, false)) {
                    emptySnapshot.countDown();
                    await(releaseSnapshot);
                }
                return tasks;
            }
        };
        var operation = mock(BaseDurableOperation.class);
        when(operation.getOperationId()).thenReturn("already-registered");
        when(operation.getType()).thenReturn(OperationType.STEP);
        manager.registerOperation(operation);
        var bodies = new AtomicInteger();
        try {
            var closing = callers.submit(manager::close);
            await(emptySnapshot);
            var late = callers.submit(() -> {
                submitter.set(Thread.currentThread());
                submitted.countDown();
                assertThrows(
                        RejectedExecutionException.class,
                        () -> manager.submitOperationTask(operation, bodies::incrementAndGet));
            });
            await(submitted);
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            var observed = submitter.get().getState();
            while (observed != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
                observed = submitter.get().getState();
            }
            // Keep the observed contention, rather than race a second thread-state snapshot.
            assertEquals(Thread.State.BLOCKED, observed);
            releaseSnapshot.countDown();
            closing.get(5, TimeUnit.SECONDS);
            late.get(5, TimeUnit.SECONDS);
            assertEquals(0, bodies.get());
        } finally {
            releaseSnapshot.countDown();
            stop(users);
            stop(callers);
            manager.close();
        }
    }

    private static ExecutionManager manager(ExecutorService users, Context context) {
        var result = new ExecutionManager(
                input(),
                DurableConfig.builder()
                        .withExecutorService(users)
                        .withDurableExecutionClient(TestUtils.createMockClient())
                        .withCheckpointDelay(Duration.ofHours(1))
                        .build(),
                context);
        result.registerActiveThread(null);
        return result;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                ARN,
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("execution")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.now())
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
    }

    private static Context context(int millis) {
        var context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn("cleanup-request");
        when(context.getRemainingTimeInMillis()).thenReturn(millis);
        return context;
    }

    private static UnrecoverableDurableExecutionException cutoff() {
        return new UnrecoverableDurableExecutionException(
                ErrorObject.builder()
                        .errorType("software.amazon.lambda.durable.execution.InvocationLifecycleException")
                        .errorMessage("controlled deadline cutoff")
                        .build(),
                true,
                new TimeoutException());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Fixture gate must be released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // This bounded fixture is released in finally; it models an uncooperative customer callback.
            }
        }
    }

    private static void stop(ExecutorService executor) throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
