// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.*;

@Timeout(20)
class DrainingAdmissionCancellationTest {
    enum Timing {
        AFTER_FINAL_BROADCAST,
        DURING_INSERTION,
        GRACEFUL
    }

    @ParameterizedTest
    @CsvSource({
        "AFTER_FINAL_BROADCAST,false",
        "AFTER_FINAL_BROADCAST,true",
        "DURING_INSERTION,false",
        "DURING_INSERTION,true",
        "GRACEFUL,false",
        "GRACEFUL,true"
    })
    void replayedWaitCannotMissCancellationButGracefulDescendantsRemainAllowed(Timing timing, boolean plugins)
            throws Exception {
        var childId = new OperationIdGenerator(null).nextOperationId();
        var waitId = new OperationIdGenerator(childId).nextOperationId();
        var childEntered = new CountDownLatch(1);
        var releaseChild = new CountDownLatch(1);
        var childExited = new CountDownLatch(1);
        var interruptedChild = new CountDownLatch(1);
        var beforeInsertion = new CountDownLatch(1);
        var releaseInsertion = new CountDownLatch(1);
        var manager = new AtomicReference<ExecutionManager>();
        var caller = new AtomicReference<Thread>();
        var rootOwner = new AtomicReference<Thread>();
        var endOwner = new AtomicReference<Thread>();
        var end = new AtomicReference<InvocationEndInfo>();
        var ends = new AtomicInteger();
        var stepBodies = new AtomicInteger();
        var failureSeen = new AtomicReference<Throwable>();
        var lateWait = new AtomicReference<BaseDurableOperation>();
        var callerInterrupted = new AtomicBoolean();
        var workers = Executors.newCachedThreadPool();
        var callers = Executors.newSingleThreadExecutor();
        var client = TestUtils.createMockClient();
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO);
        if (plugins)
            config.withPlugins(info -> new DurableExecutionPlugin() {
                public void onInvocationEnd(InvocationEndInfo info) {
                    endOwner.set(Thread.currentThread());
                    end.set(info);
                    ends.incrementAndGet();
                }
            });
        var runtime = mock(Context.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(timing == Timing.AFTER_FINAL_BROADCAST ? 3000 : 10_000);
        when(runtime.getAwsRequestId()).thenReturn("draining-admission");
        var pending = timing != Timing.GRACEFUL;
        var history = CheckpointUpdatedExecutionState.builder()
                .operations(
                        Operation.builder()
                                .id("exec")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build(),
                        Operation.builder()
                                .id(childId)
                                .name("child")
                                .type(OperationType.CONTEXT)
                                .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                                .status(OperationStatus.STARTED)
                                .build(),
                        Operation.builder()
                                .id(waitId)
                                .parentId(childId)
                                .name("old-wait")
                                .type(OperationType.WAIT)
                                .subType(OperationSubType.WAIT.getValue())
                                .status(pending ? OperationStatus.PENDING : OperationStatus.SUCCEEDED)
                                .waitDetails(WaitDetails.builder()
                                        .scheduledEndTimestamp(Instant.now().plusSeconds(3600))
                                        .build())
                                .build())
                .build();
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/draining/exec",
                "token",
                history);
        Future<?> response = null;
        try {
            response = callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input,
                            runtime,
                            TypeToken.get(String.class),
                            (value, root) -> {
                                rootOwner.set(Thread.currentThread());
                                var execution = ((DurableContextImpl) root).getExecutionManager();
                                manager.set(execution);
                                if (timing == Timing.DURING_INSERTION)
                                    installInsertionGate(
                                            execution, waitId, beforeInsertion, releaseInsertion, lateWait);
                                root.runInChildContextAsync("child", String.class, child -> {
                                    childEntered.countDown();
                                    awaitIgnoringInterrupt(releaseChild, interruptedChild);
                                    try {
                                        var wait = child.waitAsync("old-wait", Duration.ofHours(1));
                                        lateWait.set((BaseDurableOperation) wait);
                                        wait.get();
                                        if (timing == Timing.GRACEFUL)
                                            child.step("new-descendant", String.class, step -> {
                                                stepBodies.incrementAndGet();
                                                return "step";
                                            });
                                    } catch (Throwable failure) {
                                        failureSeen.set(failure);
                                    } finally {
                                        childExited.countDown();
                                    }
                                    return "child";
                                });
                                await(childEntered);
                                return "root";
                            },
                            config.build());
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            await(childEntered);
            awaitCondition(() -> inMethod(caller.get(), "awaitTasksUntil"));
            if (timing == Timing.DURING_INSERTION) {
                releaseChild.countDown();
                await(beforeInsertion);
                caller.get().interrupt();
                awaitCondition(() -> manager.get().getLifecycleFailure() != null
                        && caller.get().getState() == Thread.State.BLOCKED
                        && inMethod(caller.get(), "awaitTasksUntil"));
                assertFalse(
                        inMethod(caller.get(), "signalLifecycleFailure"), "Cancellation broadcast must already finish");
                releaseInsertion.countDown();
            } else if (timing == Timing.AFTER_FINAL_BROADCAST) {
                caller.get().interrupt();
                await(interruptedChild);
                awaitCondition(() -> inMethod(caller.get(), "awaitActualInvocationTasks"));
                releaseChild.countDown();
            } else {
                assertEquals(
                        ExecutionManager.LifecycleState.DRAINING, manager.get().getLifecycleState());
                assertNull(manager.get().getLifecycleFailure());
                releaseChild.countDown();
            }
            assertTrue(
                    childExited.await(700, TimeUnit.MILLISECONDS),
                    "A cancelled descendant must reject the old PENDING wait, not block beyond the final broadcast");
            if (pending) {
                var rejected = assertInstanceOf(UnrecoverableDurableExecutionException.class, failureSeen.get());
                assertSame(manager.get().getLifecycleFailure(), rejected);
                var observedResponse = response;
                var callerFailure = assertThrows(ExecutionException.class, () -> responseGet(observedResponse));
                assertSame(rejected, callerFailure.getCause());
                assertTrue(callerInterrupted.get());
                assertEquals(0, stepBodies.get());
                verify(client, never()).checkpoint(any(), any(), any());
            } else {
                var result = (DurableExecutionOutput) response.get(5, TimeUnit.SECONDS);
                assertEquals(ExecutionStatus.SUCCEEDED, result.status());
                assertNull(failureSeen.get());
                assertEquals(1, stepBodies.get());
                assertFalse(callerInterrupted.get());
            }
            assertEquals(plugins ? 1 : 0, ends.get());
            if (plugins) {
                assertSame(rootOwner.get(), endOwner.get());
                assertEquals(
                        pending ? InvocationStatus.RETRYING : InvocationStatus.SUCCEEDED,
                        end.get().invocationStatus());
            }
        } finally {
            releaseChild.countDown();
            releaseInsertion.countDown();
            var waiting = lateWait.get();
            if (waiting != null && !waiting.getCompletionFuture().isDone())
                waiting.getCompletionFuture()
                        .completeExceptionally(new IllegalStateException("test teardown releases blocked wait"));
            if (response != null) {
                try {
                    response.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException expected) {
                }
            }
            workers.shutdownNow();
            callers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Object responseGet(Future<?> response) throws Exception {
        return response.get(5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static void installInsertionGate(
            ExecutionManager manager,
            String target,
            CountDownLatch entered,
            CountDownLatch release,
            AtomicReference<BaseDurableOperation> rescue) {
        try {
            var field = ExecutionManager.class.getDeclaredField("registeredOperations");
            field.setAccessible(true);
            var original = (Map<String, BaseDurableOperation>) field.get(manager);
            var gated = new ConcurrentHashMap<String, BaseDurableOperation>(original) {
                @Override
                public BaseDurableOperation put(String id, BaseDurableOperation operation) {
                    if (id.equals(target)) {
                        rescue.set(operation);
                        entered.countDown();
                        awaitIgnoringInterrupt(release, new CountDownLatch(0));
                    }
                    return super.put(id, operation);
                }
            };
            field.set(manager, gated);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean inMethod(Thread thread, String method) {
        return thread != null
                && Arrays.stream(thread.getStackTrace())
                        .anyMatch(frame -> frame.getMethodName().equals(method));
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Fixture did not reach the required admission/broadcast phase");
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            assertTrue(gate.await(8, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch gate, CountDownLatch interrupted) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (true) {
            try {
                assertTrue(gate.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                return;
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }
}
