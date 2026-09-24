// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** An executor task owned by one durable invocation. */
final class InvocationTask<T> {

    enum Kind {
        ROOT,
        STEP,
        CHILD_CONTEXT,
        COORDINATOR
    }

    enum State {
        REGISTERED,
        RUNNING,
        EXITED
    }

    private final long id;
    private final Kind kind;
    private final String operationId;
    private final Runnable onExit;
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final CompletableFuture<Void> exit = new CompletableFuture<>();
    private final AtomicReference<Future<?>> execution = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.REGISTERED);
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicBoolean interruptRequested = new AtomicBoolean();

    InvocationTask(long id, Kind kind, String operationId, Runnable onExit) {
        this.id = id;
        this.kind = kind;
        this.operationId = operationId;
        this.onExit = onExit;
    }

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

    void bindExecution(Future<?> future) {
        if (!execution.compareAndSet(null, future)) {
            throw new IllegalStateException("Invocation task already has an execution future");
        }
        if (cancellationRequested.get()) {
            cancelExecution(future);
        }
    }

    void submissionFailed(Throwable failure) {
        completion.completeExceptionally(failure);
        markExited();
    }

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
            exit.complete(null);
            onExit.run();
        }
        return true;
    }

    private void markExited() {
        state.set(State.EXITED);
        exit.complete(null);
        onExit.run();
    }

    long id() {
        return id;
    }

    Kind kind() {
        return kind;
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
