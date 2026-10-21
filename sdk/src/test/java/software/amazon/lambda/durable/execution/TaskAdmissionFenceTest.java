// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

@Timeout(15)
class TaskAdmissionFenceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registeredOperationCannotDispatchAfterLifecycleFence(boolean duringRegistration) throws Exception {
        var actionCalls = new AtomicInteger();
        var dispatches = new AtomicInteger();
        var lookups = new AtomicInteger();
        var submitter = new AtomicReference<Thread>();
        var lookupEntered = new CountDownLatch(1);
        var releaseLookup = new CountDownLatch(1);
        var users = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void execute(Runnable action) {
                dispatches.incrementAndGet();
                super.execute(action);
            }
        };
        var callers = Executors.newSingleThreadExecutor();
        var input = new DurableExecutionInput(
                "arn:execution/test",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("test")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .build())
                        .build());
        var manager = new ExecutionManager(
                input,
                DurableConfig.builder()
                        .withExecutorService(users)
                        .withDurableExecutionClient(TestUtils.createMockClient())
                        .build(),
                null);
        var operation = mock(BaseDurableOperation.class);
        when(operation.getOperationId()).thenAnswer(ignored -> {
            if (duringRegistration && Thread.currentThread() == submitter.get() && lookups.incrementAndGet() == 2) {
                lookupEntered.countDown();
                try {
                    assertTrue(releaseLookup.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
            }
            return "registered-step";
        });
        when(operation.getType()).thenReturn(OperationType.STEP);
        when(operation.getCompletionFuture()).thenReturn(new CompletableFuture<>());
        manager.registerOperation(operation);
        manager.beginDraining();
        Future<?> submission = null;
        UnrecoverableDurableExecutionException fence = null;
        try {
            if (!duringRegistration) fence = selectInterruption(manager);
            submission = callers.submit(() -> {
                submitter.set(Thread.currentThread());
                manager.submitOperationTask(operation, actionCalls::incrementAndGet)
                        .join();
            });
            if (duringRegistration) {
                assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
                fence = selectInterruption(manager);
                releaseLookup.countDown();
            }
            var observed = submission;
            var failure = assertThrows(ExecutionException.class, () -> observed.get(5, TimeUnit.SECONDS));
            assertSame(fence, failure.getCause());
            assertEquals(0, dispatches.get(), "A task fenced during registration must not be offered to the executor");
            assertEquals(0, actionCalls.get());
            assertTrue(
                    manager.getActiveExecutorTasks().isEmpty(),
                    "Rejected registration must release actual-exit tracking");
        } finally {
            releaseLookup.countDown();
            if (submission != null) {
                try {
                    submission.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException expected) {
                }
            }
            try {
                manager.close();
            } catch (UnrecoverableDurableExecutionException expected) {
            } finally {
                Thread.interrupted();
            }
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static UnrecoverableDurableExecutionException selectInterruption(ExecutionManager manager) {
        Thread.currentThread().interrupt();
        var selected =
                manager.awaitInvocationOutcome(new CompletableFuture<Void>()).failure();
        assertFalse(
                Thread.currentThread().isInterrupted(),
                "The blocking observation consumes the interrupt before cleanup");
        return assertInstanceOf(UnrecoverableDurableExecutionException.class, selected);
    }
}
