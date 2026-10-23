// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;

@Timeout(20)
class OwnerDrainLifecycleFailureTest {
    enum Stop {
        CALLER,
        EXPIRY,
        QUEUED,
        QUEUED_OWNER,
        OWNER,
        NORMAL,
        ALIAS,
        TRANSPORT,
        FATAL
    }

    @ParameterizedTest
    @CsvSource({
        "CALLER,true",
        "CALLER,false",
        "EXPIRY,true",
        "QUEUED,true",
        "QUEUED_OWNER,true",
        "OWNER,true",
        "NORMAL,true",
        "ALIAS,true",
        "TRANSPORT,true",
        "FATAL,true"
    })
    void ownerDrainKeepsTheRightFailureAndActualExitBoundary(Stop stop, boolean plugins) throws Exception {
        var rootOwner = new AtomicReference<Thread>();
        var runtimeCaller = new AtomicReference<Thread>();
        var manager = new AtomicReference<ExecutionManager>();
        var end = new AtomicReference<InvocationEndInfo>();
        var endCalls = new AtomicInteger();
        var stepBodies = new AtomicInteger();
        var stepExited = new AtomicBoolean();
        var stepEntered = new CountDownLatch(1);
        var stepInterrupted = new CountDownLatch(1);
        var releaseStep = new CountDownLatch(1);
        var rootReturning = new CountDownLatch(1);
        var ignoredRootInterrupt = new CountDownLatch(1);
        var uncaught = new AtomicReference<Throwable>();
        var fatalEscaped = new CountDownLatch(1);
        var callerInterrupted = new AtomicBoolean();
        var suppressRootInterrupt = new AtomicBoolean(stop == Stop.QUEUED || stop == Stop.TRANSPORT);
        var queued = stop == Stop.QUEUED || stop == Stop.QUEUED_OWNER;
        var transport = new IllegalStateException(
                "transport cleanup interrupted", new InterruptedException("transport interruption"));
        var fatal = new InternalError("fatal transport cleanup");
        ThreadFactory threadFactory = command -> {
            var thread = new Thread(command, "owner-drain-worker") {
                @Override
                public void interrupt() {
                    if (suppressRootInterrupt.get() && this == rootOwner.get()) {
                        ignoredRootInterrupt.countDown();
                        return;
                    }
                    super.interrupt();
                }
            };
            thread.setUncaughtExceptionHandler((owner, failure) -> {
                uncaught.set(failure);
                fatalEscaped.countDown();
            });
            return thread;
        };
        var users = queued
                ? new ThreadPoolExecutor(
                        1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), threadFactory)
                : new ThreadPoolExecutor(
                        0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), threadFactory);
        var callers = Executors.newSingleThreadExecutor();
        var builder = DurableConfig.builder()
                .withExecutorService(users)
                .withDurableExecutionClient(TestUtils.createMockClient());
        if (plugins)
            builder.withPlugins(info -> new DurableExecutionPlugin() {
                public void onInvocationEnd(InvocationEndInfo info) {
                    assertSame(rootOwner.get(), Thread.currentThread());
                    if (!queued) assertTrue(stepExited.get(), "End cannot precede actual step exit");
                    end.set(info);
                    endCalls.incrementAndGet();
                }
            });
        var runtime = mock(Context.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(stop == Stop.EXPIRY ? 2500 : 20_000);
        var input = new DurableExecutionInput(
                "arn:execution/test",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("test")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
        try {
            var response = callers.submit(() -> {
                runtimeCaller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input,
                            runtime,
                            TypeToken.get(String.class),
                            (value, root) -> {
                                rootOwner.set(Thread.currentThread());
                                var execution = ((DurableContextImpl) root).getExecutionManager();
                                manager.set(execution);
                                if (stop == Stop.ALIAS || stop == Stop.TRANSPORT || stop == Stop.FATAL)
                                    installShutdownFault(
                                            execution,
                                            rootOwner,
                                            () -> stop == Stop.ALIAS
                                                    ? new CompletionException(execution.getLifecycleFailure())
                                                    : stop == Stop.FATAL ? fatal : transport);
                                root.stepAsync("held", String.class, step -> {
                                    stepBodies.incrementAndGet();
                                    stepEntered.countDown();
                                    try {
                                        awaitIgnoringInterrupt(releaseStep, stepInterrupted);
                                        return "step";
                                    } finally {
                                        stepExited.set(true);
                                    }
                                });
                                if (!queued) await(stepEntered);
                                rootReturning.countDown();
                                return "root-result";
                            },
                            builder.build());
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            await(rootReturning);
            awaitCondition(() -> inMethod(rootOwner.get(), "awaitUserHandler"));
            if (stop == Stop.NORMAL) {
                assertEquals(0, endCalls.get());
                releaseStep.countDown();
                assertEquals(
                        ExecutionStatus.SUCCEEDED,
                        response.get(5, TimeUnit.SECONDS).status());
                assertEquals(InvocationStatus.SUCCEEDED, end.get().invocationStatus());
                assertNull(end.get().executionError());
            } else {
                if (stop == Stop.OWNER) {
                    rootOwner.get().interrupt();
                    awaitCondition(
                            () -> !rootOwner.get().isInterrupted() && inMethod(rootOwner.get(), "awaitUserHandler"));
                } else if (stop == Stop.QUEUED_OWNER) {
                    var stepTask = manager.get().getActiveExecutorTasks().stream()
                            .filter(task -> task.role() == ExecutorTaskHandle.Role.STEP)
                            .findFirst()
                            .orElseThrow();
                    assertTrue(stepTask.cancel(false));
                } else {
                    if (stop != Stop.EXPIRY) runtimeCaller.get().interrupt();
                    if (stop == Stop.QUEUED) await(ignoredRootInterrupt);
                    else await(stepInterrupted);
                }
                if (!queued) {
                    assertThrows(TimeoutException.class, () -> response.get(100, TimeUnit.MILLISECONDS));
                    assertEquals(0, endCalls.get());
                    releaseStep.countDown();
                }
                var observed = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS))
                        .getCause();
                var lifecycle = manager.get().getLifecycleFailure();
                if (stop == Stop.FATAL) {
                    assertSame(fatal, observed);
                    assertSame(fatal, end.get().executionError());
                    await(fatalEscaped);
                    assertSame(fatal, uncaught.get());
                } else if (stop == Stop.TRANSPORT) {
                    assertSame(lifecycle, observed);
                    assertSame(
                            transport,
                            end.get().executionError(),
                            "An unrelated transport failure is not a synthetic owner cancellation");
                } else if (stop == Stop.OWNER || stop == Stop.QUEUED_OWNER) {
                    assertNull(lifecycle);
                    assertEquals(IllegalStateException.class, observed.getClass());
                    assertSame(observed, end.get().executionError());
                    assertTrue(
                            stop == Stop.OWNER
                                    ? observed.getCause() instanceof InterruptedException
                                    : observed.getCause() instanceof CancellationException);
                } else {
                    assertNotNull(lifecycle);
                    assertSame(lifecycle, observed);
                    if (plugins)
                        assertSame(
                                lifecycle, end.get().executionError(), "End must retain the original lifecycle cause");
                    var diagnostic = Arrays.stream(lifecycle.getSuppressed())
                            .filter(failure -> failure.getClass() == IllegalStateException.class
                                    && (failure.getCause() instanceof InterruptedException
                                            || failure.getCause() instanceof CancellationException))
                            .toList();
                    assertEquals(1, diagnostic.size(), "Keep the original synthetic drain error exactly once");
                    assertFalse(
                            references(diagnostic.get(0), lifecycle),
                            "The diagnostic must not reference its canonical lifecycle owner");
                }
                if (plugins) assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
            }
            assertEquals(plugins ? 1 : 0, endCalls.get());
            assertEquals(queued ? 0 : 1, stepBodies.get());
            assertEquals(
                    stop != Stop.NORMAL && stop != Stop.OWNER && stop != Stop.QUEUED_OWNER && stop != Stop.EXPIRY,
                    callerInterrupted.get());
        } finally {
            releaseStep.countDown();
            suppressRootInterrupt.set(false);
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void installShutdownFault(
            ExecutionManager manager, AtomicReference<Thread> owner, Supplier<Throwable> fault) {
        try {
            var field = ExecutionManager.class.getDeclaredField("checkpointManager");
            field.setAccessible(true);
            var checkpoint = spy((CheckpointManager) field.get(manager));
            doAnswer(call -> {
                        if (Thread.currentThread() != owner.get()) return call.callRealMethod();
                        try {
                            call.callRealMethod();
                        } finally {
                            throw fault.get();
                        }
                    })
                    .when(checkpoint)
                    .shutdown();
            field.set(manager, checkpoint);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean references(Throwable root, Throwable target) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            var next = pending.remove();
            if (next == target) return true;
            if (!seen.add(next)) continue;
            if (next.getCause() != null) pending.add(next.getCause());
            pending.addAll(Arrays.asList(next.getSuppressed()));
        }
        return false;
    }

    private static boolean inMethod(Thread thread, String name) {
        return thread != null
                && Arrays.stream(thread.getStackTrace())
                        .anyMatch(frame -> frame.getMethodName().equals(name));
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline);
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            assertTrue(gate.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch gate, CountDownLatch interrupted) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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
