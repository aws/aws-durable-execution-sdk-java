// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * <p>Each {@link WorkflowInsightRecord} is a complete snapshot of <em>its</em> execution, so a newer record for the
 * same execution fully supersedes any record of that execution still waiting to be exported. While an export is in
 * flight, additional updates are coalesced into a single pending slot per execution — intermediate records are dropped
 * because the latest one already contains all of their information. Records of different executions never displace each
 * other: one execution's snapshot carries none of another's information, so each execution keeps its own slot and the
 * slots are served in the order the executions first became pending. This prevents overlapping {@code export()} calls
 * when updates arrive faster than the exporters can keep up, and it keeps exporter I/O off the SDK threads that deliver
 * plugin hooks.
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

    /**
     * The latest record of each execution not yet picked up by the pump, keyed by execution ARN and served in the order
     * the executions first became pending. Guarded by {@code this}.
     */
    private final LinkedHashMap<String, WorkflowInsightRecord> pending = new LinkedHashMap<>();

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
     * Queues the latest record of its execution for export. If an export is already running, the record is held in that
     * execution's pending slot (replacing any earlier pending record of the same execution) and exported once the
     * in-flight export completes.
     */
    void schedule(WorkflowInsightRecord record) {
        CompletableFuture<Void> handle;
        synchronized (this) {
            // put() on an existing key keeps its position, so a chatty execution cannot jump ahead of a quieter one.
            pending.put(executionKey(record), record);
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
            // and drain() runs whatever is still pending on the calling thread before the invocation returns. Complete
            // the handle too: a drain() that already observed it must wake up and take that inline path.
            synchronized (this) {
                if (inFlight == handle) {
                    inFlight = null;
                }
            }
            handle.complete(null);
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
                    if (pending.isEmpty()) {
                        return;
                    }
                    // Records are pending with no pump running (a worker could not be started): export them here.
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
            // Serve pending executions in order until none is left. Taking a record and returning to idle both happen
            // under the lock, so an update scheduled at any point is either exported by this pump or starts the next
            // one — never lost.
            while (true) {
                WorkflowInsightRecord record;
                synchronized (this) {
                    Iterator<WorkflowInsightRecord> head = pending.values().iterator();
                    if (!head.hasNext()) {
                        inFlight = null;
                        return;
                    }
                    record = head.next();
                    head.remove();
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

    private static String executionKey(WorkflowInsightRecord record) {
        return record.executionArn() != null ? record.executionArn() : "";
    }

    /**
     * Flushes every exporter, each on its own worker, and waits for all of them to settle. A slow or failing flush on
     * one exporter never delays or fails the others.
     */
    void flushAll() {
        forEachExporterSettled(InsightExporter::flush);
    }

    /**
     * Exports one record to every exporter, each on its own worker, and waits for all of them to settle. One failing or
     * slow exporter never blocks or fails the others, and an export error never propagates into the execution.
     */
    private void exportToAll(WorkflowInsightRecord record) {
        forEachExporterSettled(exporter -> exportOne.accept(record, exporter));
    }

    /** Runs the action for every exporter concurrently and returns once all have settled, reporting each failure. */
    private void forEachExporterSettled(Consumer<InsightExporter> action) {
        if (exporters.size() == 1) {
            runSafely(() -> action.accept(exporters.get(0)));
            return;
        }
        List<CompletableFuture<Void>> settled = new ArrayList<>(exporters.size());
        for (InsightExporter exporter : exporters) {
            Runnable task = () -> runSafely(() -> action.accept(exporter));
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
