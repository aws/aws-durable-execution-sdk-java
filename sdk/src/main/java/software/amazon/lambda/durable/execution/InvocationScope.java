// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Owns task admission and lifecycle metadata for one Lambda invocation. */
final class InvocationScope {

    enum State {
        /** The invocation accepts new durable operations, executor tasks, checkpoints, and polls. */
        OPEN,

        /**
         * Invocation shutdown has started. New operations and tasks are rejected, while already-admitted work may still
         * checkpoint or poll as it finishes.
         */
        DRAINING,

        /** Invocation cleanup has ended; all new operations, tasks, checkpoints, and polls are rejected. */
        CLOSED
    }

    private static final AtomicLong LOCAL_INVOCATION_SEQUENCE = new AtomicLong();

    private final Object admissionLock = new Object();
    private final String invocationId;
    private final Long deadlineNanos;
    private final AtomicLong taskSequence = new AtomicLong();
    private final ConcurrentHashMap<Long, ExecutorTaskHandle<?>> tasks = new ConcurrentHashMap<>();
    private State state = State.OPEN;

    InvocationScope(Context lambdaContext, String durableExecutionArn) {
        this.invocationId = resolveInvocationId(lambdaContext, durableExecutionArn);
        this.deadlineNanos = resolveDeadlineNanos(lambdaContext);
    }

    <T> CompletableFuture<T> submit(
            ExecutorTaskHandle.Role role, String operationId, ExecutorService executor, Supplier<T> action) {
        ExecutorTaskHandle<T> task;
        synchronized (admissionLock) {
            requireOpen("task");
            var taskId = taskSequence.incrementAndGet();
            task = new ExecutorTaskHandle<>(taskId, role, operationId, () -> tasks.remove(taskId));
            tasks.put(task.id(), task);
        }

        try {
            task.bindExecution(executor.submit(() -> task.run(action)));
            return task.completion();
        } catch (RuntimeException | Error failure) {
            task.submissionFailed(failure);
            throw failure;
        }
    }

    void admitOperation(Runnable registration) {
        synchronized (admissionLock) {
            requireOpen("durable operation");
            registration.run();
        }
    }

    <T> T admitCheckpoint(Supplier<T> request) {
        synchronized (admissionLock) {
            if (state == State.CLOSED) {
                throw rejected("checkpoint request");
            }
            return request.get();
        }
    }

    void beginDraining() {
        synchronized (admissionLock) {
            if (state == State.OPEN) {
                state = State.DRAINING;
            }
        }
    }

    void close() {
        synchronized (admissionLock) {
            state = State.CLOSED;
        }
    }

    String invocationId() {
        return invocationId;
    }

    State state() {
        synchronized (admissionLock) {
            return state;
        }
    }

    Optional<Duration> remainingTime() {
        if (deadlineNanos == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())));
    }

    List<ExecutorTaskHandle<?>> tasks() {
        return tasks.values().stream()
                .sorted(Comparator.comparingLong(ExecutorTaskHandle::id))
                .toList();
    }

    private void requireOpen(String workType) {
        if (state != State.OPEN) {
            throw rejected(workType);
        }
    }

    private RejectedExecutionException rejected(String workType) {
        return new RejectedExecutionException(
                "Invocation " + invocationId + " is " + state + "; cannot admit new " + workType);
    }

    private static String resolveInvocationId(Context lambdaContext, String durableExecutionArn) {
        if (lambdaContext != null) {
            var requestId = lambdaContext.getAwsRequestId();
            if (requestId != null && !requestId.isBlank()) {
                return requestId;
            }
        }
        return durableExecutionArn + "#local-" + LOCAL_INVOCATION_SEQUENCE.incrementAndGet();
    }

    private static Long resolveDeadlineNanos(Context lambdaContext) {
        if (lambdaContext == null) {
            return null;
        }
        var remainingMillis = Math.max(0L, lambdaContext.getRemainingTimeInMillis());
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(remainingMillis);
        var now = System.nanoTime();
        return now > Long.MAX_VALUE - remainingNanos ? Long.MAX_VALUE : now + remainingNanos;
    }
}
