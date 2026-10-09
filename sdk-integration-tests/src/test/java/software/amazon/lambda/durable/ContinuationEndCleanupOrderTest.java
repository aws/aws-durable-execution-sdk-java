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
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;
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
import software.amazon.lambda.durable.util.ExceptionHelper;

class ContinuationEndCleanupOrderTest {
    static Stream<Arguments> cleanupCases() {
        return Stream.of("async", "inline-root", "submit-root")
                .flatMap(mode -> Stream.of("serde", "reject", "vm", "death").map(kind -> Arguments.of(mode, kind)));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("cleanupCases")
    void continuationFailureMustWakeBodyBeforeEndWaitsForCleanup(String mode, String kind) throws Exception {
        boolean rejection = kind.equals("reject");
        boolean withPlugin = true;
        Throwable failure =
                switch (kind) {
                    case "reject" -> new RejectedExecutionException("resumed worker rejected");
                    case "vm" -> new InternalError("resumed state fatal");
                    case "death" -> new ThreadDeath();
                    default -> new IllegalArgumentException("resumed state cannot deserialize");
                };
        var injectionThread = new AtomicReference<Thread>();
        var callerThread = new AtomicReference<Thread>();
        var escaped = new CountDownLatch(1);
        var escapedFailure = new AtomicReference<Throwable>();
        var oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (error == failure) {
                escapedFailure.set(error);
                escaped.countDown();
            } else if (oldHandler != null) oldHandler.uncaughtException(thread, error);
        });
        var managerForCleanup = new AtomicReference<ExecutionManager>();
        var armed = new AtomicBoolean();
        var injected = new CountDownLatch(1);
        var injectionOwner = new AtomicReference<String>();
        var checks = new AtomicInteger();
        var bodyFinally = new CountDownLatch(1);
        var cleanupOrder = new CopyOnWriteArrayList<String>();
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
                    injectionThread.set(Thread.currentThread());
                    injectionOwner.set(Thread.currentThread().getName());
                    onCoordinator.set(Arrays.stream(Thread.currentThread().getStackTrace())
                            .anyMatch(frame -> frame.getMethodName().equals("completeCheckpointContinuation")));
                    injected.countDown();
                    ExceptionHelper.sneakyThrow(failure);
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
        var workers = new RootExecutor(mode, () -> {
            if (rejection && armed.get() && Thread.currentThread().getName().startsWith("durable-sdk-internal-")) {
                injectionThread.set(Thread.currentThread());
                injectionOwner.set(Thread.currentThread().getName());
                onCoordinator.set(Arrays.stream(Thread.currentThread().getStackTrace())
                        .anyMatch(frame -> frame.getMethodName().equals("completeCheckpointContinuation")));
                injected.countDown();
                ExceptionHelper.sneakyThrow(failure);
            }
        });
        var caller = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "legacy-outcome-caller");
            callerThread.set(thread);
            return thread;
        });
        var builder = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO)
                .withPollingStrategy(attempt -> Duration.ZERO);
        if (withPlugin)
            builder.withPlugins(new DurableExecutionPlugin() {
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.add(info);
                    if (info.invocationStatus() == InvocationStatus.RETRYING) {
                        assertSame(
                                mode.equals("async") ? injectionThread.get() : callerThread.get(),
                                Thread.currentThread(),
                                "The repair must preserve the legacy End completion thread");
                        cleanupOrder.add("end-enter");
                        try {
                            boolean finished = bodyFinally.await(2, TimeUnit.SECONDS);
                            cleanupOrder.add(finished ? "end-complete" : "end-timed-out");
                            assertTrue(finished, "End must not prevent the manager from waking handler cleanup");
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                        }
                    }
                }
            });
        var config = builder.build();
        var condition = WaitForConditionConfig.<Integer>builder()
                .initialState(1)
                .serDes(serde)
                .waitStrategy((state, attempt) -> Duration.ofSeconds(1))
                .build();
        BiFunction<String, DurableContext, String> handler = (input, context) -> {
            managerForCleanup.set(((BaseContextImpl) context).getExecutionManager());
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
            try {
                return String.valueOf(waiting.get());
            } finally {
                if (armed.get()) {
                    cleanupOrder.add("body-finally");
                    bodyFinally.countDown();
                }
            }
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
            workers.nextInvocation();
            var second = caller.submit(() ->
                    DurableExecutor.execute(input(cachedPending), null, TypeToken.get(String.class), handler, config));
            pendingCall.set(second);
            assertTrue(pollEntered.await(3, TimeUnit.SECONDS));
            if (mode.equals("async")) awaitCallerOutcomeJoin(callerThread.get());
            releaseReady.countDown();
            assertTrue(injected.await(3, TimeUnit.SECONDS));
            assertTrue(onCoordinator.get(), "Fault must run in the actual WFC coordinator continuation");
            DurableExecutionOutput output = null;
            Throwable callerFailure = null;
            try {
                output = second.get(5, TimeUnit.SECONDS);
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
            System.out.println("CONTINUATION_END_CLEANUP_ORDER " + cleanupOrder);
            assertFalse(cleanupOrder.contains("end-timed-out"));
            assertTrue(cleanupOrder.contains("end-complete"));
            assertTrue(cleanupOrder.indexOf("body-finally") < cleanupOrder.indexOf("end-complete"));
            if (failure instanceof Error) {
                assertTrue(escaped.await(3, TimeUnit.SECONDS));
                assertSame(failure, escapedFailure.get());
            }
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
            workers.nextInvocation();
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
            Thread.setDefaultUncaughtExceptionHandler(oldHandler);
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"async", "inline-root", "submit-root"})
    void ordinaryCompletionKeepsItsExistingEndThread(String mode) throws Exception {
        var releaseBody = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var callerThread = new AtomicReference<Thread>();
        var bodyThread = new AtomicReference<Thread>();
        var endThread = new AtomicReference<Thread>();
        var workers = new RootExecutor(mode, () -> {});
        var caller = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "normal-outcome-caller");
            callerThread.set(thread);
            return thread;
        });
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(new LocalMemoryExecutionClient())
                .withPlugins(new DurableExecutionPlugin() {
                    public void onInvocationEnd(InvocationEndInfo info) {
                        endThread.set(Thread.currentThread());
                    }
                })
                .build();
        try {
            var call = caller.submit(() -> DurableExecutor.execute(
                    input(List.of()),
                    null,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        bodyThread.set(Thread.currentThread());
                        entered.countDown();
                        if (mode.equals("async")) {
                            try {
                                assertTrue(releaseBody.await(3, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                throw new AssertionError(interrupted);
                            }
                        }
                        return "done";
                    },
                    config));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            if (mode.equals("async")) awaitCallerOutcomeJoin(callerThread.get());
            releaseBody.countDown();
            assertEquals(
                    ExecutionStatus.SUCCEEDED, call.get(3, TimeUnit.SECONDS).status());
            assertSame(mode.equals("async") ? bodyThread.get() : callerThread.get(), endThread.get());
        } finally {
            releaseBody.countDown();
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Wait for the actual public invocation caller to install its outcome observer and enter join, not a delay. */
    private static void awaitCallerOutcomeJoin(Thread caller) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Arrays.stream(caller.getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(CompletableFuture.class.getName())
                            && frame.getMethodName().equals("join"))) return;
            Thread.yield();
        }
        fail("Invocation caller did not reach its installed outcome observer");
    }

    /** Root dispatch exercises inline/submit-and-wait; operation children remain asynchronous as supported. */
    private static final class RootExecutor extends ThreadPoolExecutor {
        private final String mode;
        private final Runnable beforeDispatch;
        private final AtomicBoolean root = new AtomicBoolean(true);

        RootExecutor(String mode, Runnable beforeDispatch) {
            super(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>());
            this.mode = mode;
            this.beforeDispatch = beforeDispatch;
        }

        void nextInvocation() {
            root.set(true);
        }

        @Override
        public void execute(Runnable task) {
            beforeDispatch.run();
            if (root.compareAndSet(true, false)) {
                if (mode.equals("inline-root")) {
                    task.run();
                    return;
                }
                if (mode.equals("submit-root")) {
                    var done = new CompletableFuture<Void>();
                    super.execute(() -> {
                        try {
                            task.run();
                        } finally {
                            done.complete(null);
                        }
                    });
                    try {
                        done.get(5, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    return;
                }
            }
            super.execute(task);
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
