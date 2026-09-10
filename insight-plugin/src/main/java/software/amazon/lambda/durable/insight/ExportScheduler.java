// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Serializes record exports so that, at most, one export runs at a time.
 *
 * <p>Each {@link WorkflowInsightRecord} is a complete snapshot of the execution, so a newer record fully supersedes any
 * record still waiting to be exported. While an export is in flight, additional updates are coalesced into a single
 * "pending" slot — intermediate records are dropped because the latest one already contains all of their information.
 * This prevents overlapping {@code export()} calls when updates arrive faster than the exporters can keep up, and it
 * keeps exporter I/O off the SDK threads that deliver plugin hooks.
 *
 * <p>Exports are otherwise fire-and-forget; {@link #drain()} is called before the invocation returns to guarantee the
 * final record is delivered.
 */
final class ExportScheduler {

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    /** Shared for the process lifetime; idle daemon workers are reclaimed, so nothing keeps the runtime alive. */
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "workflow-insight-export-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final List<InsightExporter> exporters;
    private final BiConsumer<WorkflowInsightRecord, InsightExporter> exportOne;
    private final Consumer<Throwable> failureHandler;
    private final Executor executor;

    /** Completes when the current pump finishes; {@code null} while idle. Guarded by {@code this}. */
    private CompletableFuture<Void> inFlight;

    /** The latest record not yet picked up by the pump. Guarded by {@code this}. */
    private WorkflowInsightRecord pending;

    ExportScheduler(
            List<InsightExporter> exporters,
            BiConsumer<WorkflowInsightRecord, InsightExporter> exportOne,
            Consumer<Throwable> failureHandler) {
        this(exporters, exportOne, failureHandler, WORKERS);
    }

    ExportScheduler(
            List<InsightExporter> exporters,
            BiConsumer<WorkflowInsightRecord, InsightExporter> exportOne,
            Consumer<Throwable> failureHandler,
            Executor executor) {
        this.exporters = List.copyOf(exporters);
        this.exportOne = exportOne;
        this.failureHandler = failureHandler;
        this.executor = executor;
    }

    /**
     * Queues the latest record for export. If an export is already running, the record is held in the pending slot
     * (replacing any earlier pending record) and exported once the in-flight export completes.
     */
    void schedule(WorkflowInsightRecord record) {
        CompletableFuture<Void> handle;
        synchronized (this) {
            pending = record;
            if (inFlight != null) {
                return;
            }
            handle = new CompletableFuture<>();
            inFlight = handle;
        }
        try {
            executor.execute(() -> pump(handle));
        } catch (Throwable t) {
            // No worker could be started. Keep the pending record and return to idle so a later schedule() retries,
            // and drain() runs whatever is still pending on the calling thread before the invocation returns.
            synchronized (this) {
                if (inFlight == handle) {
                    inFlight = null;
                }
            }
            reportFailure(t);
        }
    }

    /**
     * Waits for any in-flight and pending exports to complete. Safe to call when idle. Used before the invocation
     * returns to guarantee the final record is delivered.
     */
    void drain() {
        while (true) {
            CompletableFuture<Void> handle;
            boolean runInline = false;
            synchronized (this) {
                handle = inFlight;
                if (handle == null) {
                    if (pending == null) {
                        return;
                    }
                    // A record is pending with no pump running (a worker could not be started): export it here.
                    handle = new CompletableFuture<>();
                    inFlight = handle;
                    runInline = true;
                }
            }
            if (runInline) {
                pump(handle);
            } else {
                handle.join();
            }
        }
    }

    private void pump(CompletableFuture<Void> handle) {
        try {
            // Drain the pending slot until no newer record has arrived. Taking the record and returning to idle both
            // happen under the lock, so an update scheduled at any point is either exported by this pump or starts
            // the next one — never lost.
            while (true) {
                WorkflowInsightRecord record;
                synchronized (this) {
                    record = pending;
                    pending = null;
                    if (record == null) {
                        inFlight = null;
                        return;
                    }
                }
                exportToAll(record);
            }
        } finally {
            synchronized (this) {
                if (inFlight == handle) {
                    inFlight = null;
                }
            }
            handle.complete(null);
        }
    }

    /**
     * Exports one record to every exporter, each on its own worker, and waits for all of them to settle. One failing or
     * slow exporter never blocks or fails the others, and an export error never propagates into the execution.
     */
    private void exportToAll(WorkflowInsightRecord record) {
        if (exporters.size() == 1) {
            runSafely(() -> exportOne.accept(record, exporters.get(0)));
            return;
        }
        List<CompletableFuture<Void>> settled = new ArrayList<>(exporters.size());
        for (InsightExporter exporter : exporters) {
            Runnable task = () -> runSafely(() -> exportOne.accept(record, exporter));
            try {
                settled.add(CompletableFuture.runAsync(task, executor));
            } catch (Throwable t) {
                reportFailure(t);
                task.run();
            }
        }
        for (CompletableFuture<Void> task : settled) {
            runSafely(task::join);
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
