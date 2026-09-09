// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Serializes ON_CHANGE exports through a bounded, invocation-scoped FIFO. */
final class ExportScheduler {
    static final int DEFAULT_CAPACITY = 16;

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    // Shared for the Lambda process lifetime. Cached daemon workers are reclaimed after idle periods, while each
    // invocation retains its own serial scheduler, bounded queue, and flush barrier.
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "workflow-insight-export-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final int capacity;
    private final Executor executor;
    private final Consumer<WorkflowInsightRecord> export;
    private final Runnable flush;
    private final Consumer<Throwable> failureHandler;
    private final ArrayDeque<WorkflowInsightRecord> queue = new ArrayDeque<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    private boolean running;
    private boolean sealed;

    ExportScheduler(Consumer<WorkflowInsightRecord> export, Runnable flush, Consumer<Throwable> failureHandler) {
        this(DEFAULT_CAPACITY, WORKERS, export, flush, failureHandler);
    }

    ExportScheduler(
            int capacity,
            Executor executor,
            Consumer<WorkflowInsightRecord> export,
            Runnable flush,
            Consumer<Throwable> failureHandler) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.executor = executor;
        this.export = export;
        this.flush = flush;
        this.failureHandler = failureHandler;
    }

    /** Queues a complete RUNNING snapshot, dropping the oldest waiting snapshot only under backpressure. */
    synchronized boolean schedule(WorkflowInsightRecord record) {
        if (sealed) {
            return false;
        }
        enqueue(record);
        startWorker();
        return true;
    }

    /** Seals this invocation, queues its final snapshot, and returns a future for the ordered flush barrier. */
    synchronized CompletableFuture<Void> sealAndDrain(WorkflowInsightRecord finalRecord) {
        if (!sealed) {
            sealed = true;
            if (finalRecord != null) {
                enqueue(finalRecord);
            }
            startWorker();
        }
        return drained;
    }

    private void enqueue(WorkflowInsightRecord record) {
        if (queue.size() == capacity) {
            queue.removeFirst();
        }
        queue.addLast(record);
    }

    private void startWorker() {
        if (running) {
            return;
        }
        running = true;
        try {
            executor.execute(this::pump);
        } catch (Throwable t) {
            running = false;
            reportFailure(t);
            if (sealed) {
                queue.clear();
                drained.complete(null);
            }
        }
    }

    private void pump() {
        while (true) {
            WorkflowInsightRecord record;
            synchronized (this) {
                if (!queue.isEmpty()) {
                    record = queue.removeFirst();
                } else if (sealed) {
                    record = null;
                } else {
                    running = false;
                    return;
                }
            }

            if (record != null) {
                runSafely(() -> export.accept(record));
                continue;
            }

            runSafely(flush);
            synchronized (this) {
                running = false;
                drained.complete(null);
            }
            return;
        }
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            reportFailure(t);
        }
    }

    private void reportFailure(Throwable t) {
        try {
            failureHandler.accept(t);
        } catch (Throwable ignored) {
            // A scheduler diagnostic must never disrupt durable execution.
        }
    }
}
