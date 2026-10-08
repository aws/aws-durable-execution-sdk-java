// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class ContinuationFailurePublicProbeTest {
    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void readyResumeFailureDoesNotDisappearOrHang(boolean rejection, boolean withPlugin) throws Exception {
        RuntimeException failure = rejection
                ? new RejectedExecutionException("resumed worker rejected")
                : new IllegalArgumentException("resumed state cannot deserialize");
        var managerForCleanup = new AtomicReference<ExecutionManager>();
        var armed = new AtomicBoolean();
        var injected = new CountDownLatch(1);
        var injectionOwner = new AtomicReference<String>();
        var checks = new AtomicInteger();
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var pollEntered = new CountDownLatch(1);
        var releaseReady = new CountDownLatch(1);
        var firstArmedPoll = new AtomicBoolean(true);
        var onCoordinator = new AtomicBoolean();
        var pendingCall = new AtomicReference<Future<?>>();
        var serde = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                return delegate.serialize(value);
            }

            public <T> T deserialize(String value, TypeToken<T> type) {
                if (!rejection
                        && armed.get()
                        && Thread.currentThread().getName().startsWith("durable-sdk-internal-")) {
                    injectionOwner.set(Thread.currentThread().getName());
                    onCoordinator.set(Arrays.stream(Thread.currentThread().getStackTrace())
                            .anyMatch(frame -> frame.getMethodName().equals("completeCheckpointContinuation")));
                    injected.countDown();
                    throw failure;
                }
                return delegate.deserialize(value, type);
            }
        };
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                if (armed.get() && updates.isEmpty() && firstArmedPoll.compareAndSet(true, false)) {
                    pollEntered.countDown();
                    try {
                        assertTrue(releaseReady.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                }
                return super.checkpoint(arn, token, updates);
            }
        };
        var workers =
                new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>()) {
                    @Override
                    public void execute(Runnable task) {
                        if (rejection
                                && armed.get()
                                && Thread.currentThread().getName().startsWith("durable-sdk-internal-")) {
                            injectionOwner.set(Thread.currentThread().getName());
                            onCoordinator.set(Arrays.stream(
                                            Thread.currentThread().getStackTrace())
                                    .anyMatch(frame -> frame.getMethodName().equals("completeCheckpointContinuation")));
                            injected.countDown();
                            throw failure;
                        }
                        super.execute(task);
                    }
                };
        var caller = Executors.newSingleThreadExecutor();
        var builder = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO)
                .withPollingStrategy(attempt -> Duration.ZERO);
        if (withPlugin)
            builder.withPlugins(new DurableExecutionPlugin() {
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.add(info);
                }
            });
        var config = builder.build();
        var condition = WaitForConditionConfig.<Integer>builder()
                .initialState(1)
                .serDes(serde)
                .waitStrategy((state, attempt) -> Duration.ofSeconds(1))
                .build();
        BiFunction<String, DurableContext, String> handler = (input, context) -> {
            managerForCleanup.set(
                    ((software.amazon.lambda.durable.context.BaseContextImpl) context).getExecutionManager());
            var waiting = context.waitForConditionAsync(
                    "condition",
                    Integer.class,
                    (state, step) -> {
                        checks.incrementAndGet();
                        return state == 2
                                ? WaitForConditionResult.stopPolling(state)
                                : WaitForConditionResult.continuePolling(2);
                    },
                    condition);
            if (armed.get()) {
                try {
                    assertTrue(pollEntered.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
            }
            return String.valueOf(waiting.get());
        };
        try {
            var first = caller.submit(() -> DurableExecutor.execute(
                            input(List.of()), null, TypeToken.get(String.class), handler, config))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.PENDING, first.status());
            var cachedPending = new ArrayList<>(client.getAllOperations());
            assertEquals(
                    OperationStatus.PENDING,
                    client.getOperationByName("condition").status());
            assertTrue(client.advanceTime());
            armed.set(true);
            var second = caller.submit(() ->
                    DurableExecutor.execute(input(cachedPending), null, TypeToken.get(String.class), handler, config));
            pendingCall.set(second);
            assertTrue(pollEntered.await(3, TimeUnit.SECONDS));
            releaseReady.countDown();
            assertTrue(injected.await(3, TimeUnit.SECONDS));
            assertTrue(onCoordinator.get(), "Fault must run in the actual WFC coordinator continuation");
            DurableExecutionOutput output = null;
            Throwable callerFailure = null;
            try {
                output = second.get(3, TimeUnit.SECONDS);
            } catch (ExecutionException observationFailure) {
                callerFailure = observationFailure.getCause();
            }
            System.out.println("CONTINUATION_ORDINARY_PUBLIC owner=" + injectionOwner.get()
                    + " caller="
                    + (output != null
                            ? output.status()
                            : callerFailure.getClass().getName())
                    + " backend=" + client.getOperationByName("condition").status()
                    + " checks=" + checks.get());
            assertEquals(1, checks.get(), "A failed resumed state read must not rerun the condition body");
            assertNull(output, "An SDK continuation failure must not produce a durable response");
            var retry = assertInstanceOf(UnrecoverableDurableExecutionException.class, callerFailure);
            assertTrue(retry.isRetryable());
            assertSame(failure, retry.getCause());
            assertEquals(
                    OperationStatus.READY,
                    client.getOperationByName("condition").status());
            var activeField = ExecutionManager.class.getDeclaredField("activeThreads");
            activeField.setAccessible(true);
            assertFalse(
                    ((Set<?>) activeField.get(managerForCleanup.get()))
                            .contains(client.getOperationByName("condition").id()),
                    "A rejected operation worker must not leave a phantom activity registration");
            if (withPlugin)
                assertEquals(
                        List.of(InvocationStatus.PENDING, InvocationStatus.RETRYING),
                        ends.stream().map(InvocationEndInfo::invocationStatus).toList());
            else assertTrue(ends.isEmpty());
            armed.set(false);
            var third = caller.submit(() -> DurableExecutor.execute(
                    input(client.getAllOperations()), null, TypeToken.get(String.class), handler, config));
            pendingCall.set(third);
            assertEquals(
                    ExecutionStatus.SUCCEEDED, third.get(3, TimeUnit.SECONDS).status());
            assertEquals(2, checks.get(), "Only the original and successful resumed predicates run");
        } finally {
            releaseReady.countDown();
            // Cleanup-only escape hatch AFTER the real public invocation observation/timeout; never test behavior.
            var manager = managerForCleanup.get();
            if (pendingCall.get() != null
                    && !pendingCall.get().isDone()
                    && manager != null
                    && !manager.isExecutionCompletedExceptionally()) {
                try {
                    manager.terminateExecution(new UnrecoverableDurableExecutionException(
                            ErrorObject.builder().errorMessage("probe cleanup").build(), true));
                } catch (UnrecoverableDurableExecutionException expected) {
                }
            }
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static DurableExecutionInput input(List<Operation> operations) {
        var execution = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        var history = new ArrayList<Operation>();
        history.add(execution);
        history.addAll(operations);
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/fatal/execution",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(history).build());
    }
}
