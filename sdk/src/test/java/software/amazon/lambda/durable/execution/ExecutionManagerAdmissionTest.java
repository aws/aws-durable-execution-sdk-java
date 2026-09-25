// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

class ExecutionManagerAdmissionTest {
    private final ExecutorService users = Executors.newCachedThreadPool();
    private final ExecutorService callers = Executors.newCachedThreadPool();
    private final DurableExecutionClient client = mock(DurableExecutionClient.class);
    private DurableConfig config;
    private ExecutionManager manager;

    @BeforeEach
    void setUp() {
        config = spy(DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withExecutorService(users)
                .withCheckpointDelay(Duration.ofHours(1))
                .withPollingStrategy(attempt -> Duration.ofHours(1))
                .build());
        var execution = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
        manager = new ExecutionManager(
                new DurableExecutionInput(
                        "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/execution",
                        "token",
                        CheckpointUpdatedExecutionState.builder()
                                .operations(execution)
                                .build()),
                config,
                null);
        manager.registerActiveThread(null);
        when(client.checkpoint(any(), any(), any())).thenReturn(response());
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
        stop(callers);
        stop(users);
    }

    @Test
    void pollAdmissionDoesNotBlockRetryTaskSubmission() throws Exception {
        var checkpointEntered = new CountDownLatch(1);
        var releaseCheckpoint = new CountDownLatch(1);
        var pollEntered = new CountDownLatch(1);
        var step = step("retry");
        manager.registerOperation(step);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            checkpointEntered.countDown();
            await(releaseCheckpoint);
            return response(Operation.builder()
                    .id("retry")
                    .type(OperationType.STEP)
                    .status(OperationStatus.READY)
                    .build());
        });
        doAnswer(invocation -> {
                    pollEntered.countDown();
                    return invocation.callRealMethod();
                })
                .when(config)
                .getPollingStrategy();

        try {
            var retryPoll = manager.pollForOperationUpdates("retry", Instant.now());
            await(checkpointEntered);
            var concurrentPoll = callers.submit(() -> manager.pollForOperationUpdates("other"));
            await(pollEntered);
            // The other poll is waiting for CheckpointManager's monitor. It must not retain admissionLock.
            callers.submit(() -> manager.submitOperationTask(step, () -> {})).get(5, TimeUnit.SECONDS);
            var retries = new AtomicInteger();
            var retry = retryPoll.thenCompose(op -> manager.submitOperationTask(step, retries::incrementAndGet));
            releaseCheckpoint.countDown();
            retry.get(5, TimeUnit.SECONDS);
            concurrentPoll.get(5, TimeUnit.SECONDS);
            assertEquals(1, retries.get());
        } finally {
            releaseCheckpoint.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownWaitsForAdmittedPollAndPreservesInterruption(boolean interrupted) throws Exception {
        var admissionEntered = new CountDownLatch(1);
        var releaseAdmission = new CountDownLatch(1);
        doAnswer(invocation -> {
                    admissionEntered.countDown();
                    await(releaseAdmission);
                    return invocation.callRealMethod();
                })
                .when(config)
                .getPollingStrategy();

        try {
            var admission = callers.submit(() -> manager.pollForOperationUpdates("pending"));
            await(admissionEntered);
            var shutdown = callers.submit(() -> {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                manager.close();
                return Thread.interrupted();
            });
            callers.submit(this::awaitClosedAdmission).get(5, TimeUnit.SECONDS);
            assertFalse(shutdown.isDone());
            assertThrows(RejectedExecutionException.class, () -> manager.sendOperationUpdate(update()));
            releaseAdmission.countDown();
            var poll = admission.get(5, TimeUnit.SECONDS);
            assertEquals(interrupted, shutdown.get(5, TimeUnit.SECONDS));
            assertTrue(poll.isCompletedExceptionally());
        } finally {
            releaseAdmission.countDown();
        }
    }

    @Test
    void failedAdmissionDoesNotPreventShutdown() throws Exception {
        doThrow(new IllegalArgumentException("polling configuration failed"))
                .when(config)
                .getPollingStrategy();
        assertThrows(IllegalArgumentException.class, () -> manager.pollForOperationUpdates("pending"));
        callers.submit(manager::close).get(5, TimeUnit.SECONDS);
        assertEquals(ExecutionManager.LifecycleState.CLOSED, manager.getLifecycleState());
    }

    @Test
    void finalCheckpointFlushCannotAdmitMoreCheckpointsOrPolls() throws Exception {
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            assertThrows(RejectedExecutionException.class, () -> manager.sendOperationUpdate(update()));
            assertThrows(RejectedExecutionException.class, () -> manager.pollForOperationUpdates("late"));
            assertThrows(
                    RejectedExecutionException.class, () -> manager.pollForOperationUpdates("late", Instant.now()));
            return response();
        });
        var checkpoint = manager.sendOperationUpdate(update());
        manager.close();
        checkpoint.get(5, TimeUnit.SECONDS);
    }

    @Test
    void drainingAllowsOnlyTasksOfPreviouslyRegisteredOperations() throws Exception {
        var registered = step("registered");
        manager.registerOperation(registered);
        manager.beginDraining();

        var executions = new AtomicInteger();
        manager.submitOperationTask(registered, executions::incrementAndGet).get(5, TimeUnit.SECONDS);
        assertEquals(1, executions.get());
        assertThrows(RejectedExecutionException.class, () -> manager.submitOperationTask(step("registered"), () -> {}));
        assertThrows(RejectedExecutionException.class, () -> manager.submitOperationTask(step("new"), () -> {}));
        assertThrows(RejectedExecutionException.class, () -> manager.submitRootTask(() -> null));
        manager.close();
        assertThrows(RejectedExecutionException.class, () -> manager.submitOperationTask(registered, () -> {}));
    }

    private void awaitClosedAdmission() {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (manager.getLifecycleState() != ExecutionManager.LifecycleState.CLOSED) {
            assertTrue(System.nanoTime() < deadline, "Checkpoint admission did not close");
            Thread.yield();
        }
    }

    private static BaseDurableOperation step(String id) {
        var operation = mock(BaseDurableOperation.class);
        when(operation.getOperationId()).thenReturn(id);
        when(operation.getType()).thenReturn(OperationType.STEP);
        return operation;
    }

    private static OperationUpdate update() {
        return OperationUpdate.builder()
                .id("step")
                .type(OperationType.STEP)
                .action(OperationAction.START)
                .build();
    }

    private static CheckpointDurableExecutionResponse response(Operation... operations) {
        return CheckpointDurableExecutionResponse.builder()
                .checkpointToken("next-token")
                .newExecutionState(CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(operations))
                        .build())
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Test coordination timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void stop(ExecutorService executor) throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
