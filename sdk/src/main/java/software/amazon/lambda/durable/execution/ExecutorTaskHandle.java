// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Tracks one executor task owned by a single Lambda invocation.
 *
 * <p>This is runtime lifecycle metadata, not a checkpointed durable operation and not a request to invoke another
 * Lambda function. Tasks include the root durable handler, user code for steps and child contexts, and SDK-owned
 * map/parallel coordination. A durable operation may have no active task while it is replayed or waiting, and may
 * create multiple tasks over its lifetime.
 *
 * <p>The logical {@link #completion()} future reports the action's result to SDK code. The separate {@link #exit()}
 * future proves that the executor wrapper has actually stopped running. {@link #execution()} retains the underlying
 * executor handle so a later invocation-cleanup phase can request interruption without confusing logical completion
 * with actual task exit.
 */
final class ExecutorTaskHandle<T> {

    /**
     * Runtime role used for invocation-local cleanup, diagnostics, and executor routing. This is deliberately separate
     * from a persisted durable operation type: the root has no operation, and map/parallel coordinators share the
     * CONTEXT operation type with child user code.
     */
    enum Role {
        /** The top-level durable handler submitted by {@link DurableExecutor}. */
        ROOT,

        /** User code for an operation whose type is STEP, including wait-for-condition checks. */
        STEP,

        /** User code running in a child context, map iteration, parallel branch, or retry context. */
        CHILD_CONTEXT,

        /** The SDK-owned scheduling loop for a map or parallel operation. */
        COORDINATOR
    }

    enum State {
        /** Admitted to the invocation scope but not yet started by the executor. */
        REGISTERED,

        /** The executor has entered the task wrapper and the action may still be running. */
        RUNNING,

        /** The wrapper has returned, or the executor accepted cancellation before the wrapper started. */
        EXITED
    }

    /** Scope-local sequence number; this is not a durable operation ID. */
    private final long id;

    private final Role role;

    /** Associated durable operation ID, or {@code null} for the root handler. */
    private final String operationId;

    /** Removes this task from the execution manager's active-task registry. */
    private final Runnable onExit;

    /** Logical action result consumed by the SDK; cancellation can complete it before the action exits. */
    private final CompletableFuture<T> completion = new CompletableFuture<>();

    /** Completes only when no executor thread can still be running this task. */
    private final CompletableFuture<Void> exit = new CompletableFuture<>();

    /** Handle returned by {@link java.util.concurrent.ExecutorService#submit(Runnable)}. */
    private final AtomicReference<Future<?>> execution = new AtomicReference<>();

    private final AtomicReference<State> state = new AtomicReference<>(State.REGISTERED);
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicBoolean interruptRequested = new AtomicBoolean();

    ExecutorTaskHandle(long id, Role role, String operationId, Runnable onExit) {
        this.id = id;
        this.role = role;
        this.operationId = operationId;
        this.onExit = onExit;
    }

    /** Executor wrapper that captures the logical result and always records actual exit. */
    void run(Supplier<T> action) {
        if (!state.compareAndSet(State.REGISTERED, State.RUNNING)) {
            return;
        }
        try {
            completion.complete(action.get());
        } catch (Throwable throwable) {
            completion.completeExceptionally(throwable);
        } finally {
            markExited();
        }
    }

    /**
     * Binds the executor handle after submission. A cancellation racing with submission is remembered and applied as
     * soon as this handle becomes available.
     */
    void bindExecution(Future<?> future) {
        if (!execution.compareAndSet(null, future)) {
            throw new IllegalStateException("Executor task already has an execution future");
        }
        if (cancellationRequested.get()) {
            cancelExecution(future);
        }
    }

    /** Records a synchronous executor rejection and releases the scope registration. */
    void submissionFailed(Throwable failure) {
        completion.completeExceptionally(failure);
        markExited();
    }

    /**
     * Requests cooperative cancellation through the executor handle. Logical completion is cancelled immediately;
     * actual exit remains pending until running code returns. User code that ignores interruption cannot be forcibly
     * stopped.
     */
    boolean cancel(boolean mayInterruptIfRunning) {
        if (mayInterruptIfRunning) {
            interruptRequested.set(true);
        }
        cancellationRequested.set(true);
        completion.cancel(false);
        var future = execution.get();
        if (future == null) {
            return true;
        }
        return cancelExecution(future);
    }

    private boolean cancelExecution(Future<?> future) {
        if (!future.cancel(interruptRequested.get())) {
            return false;
        }
        if (state.compareAndSet(State.REGISTERED, State.EXITED)) {
            onExit.run();
            exit.complete(null);
        }
        return true;
    }

    private void markExited() {
        state.set(State.EXITED);
        onExit.run();
        exit.complete(null);
    }

    long id() {
        return id;
    }

    Role role() {
        return role;
    }

    String operationId() {
        return operationId;
    }

    State state() {
        return state.get();
    }

    CompletableFuture<T> completion() {
        return completion;
    }

    CompletableFuture<Void> exit() {
        return exit;
    }

    Future<?> execution() {
        return execution.get();
    }
}
