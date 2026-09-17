// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Serializes record exports so that, at most, one export runs at a time, while keeping the records of concurrently
 * running executions independent.
 *
 * <p>One plugin instance — and therefore one scheduler — serves the whole execution environment, and an environment can
 * host several durable executions at the same time (Lambda Managed Instances makes that routine). So the pending work
 * is keyed by execution ARN: each execution has its own latest-record slot, and coalescing happens only <em>within</em>
 * one execution.
 *
 * <p>Each {@link WorkflowInsightRecord} is a complete snapshot of its execution, so a newer record for the same
 * execution fully supersedes one still waiting to be exported. While an export is in flight, additional updates for
 * that execution are coalesced into its slot — intermediate records are dropped because the latest one already contains
 * all of their information. A record for a <em>different</em> execution never displaces another execution's record.
 *
 * <p>A single pump exports the queued records one at a time, in the order the executions first queued work, so
 * exporters still never see two exports at once and each record keeps its per-exporter fan-out. {@link #flush()}
 * requests are served by that same pump, between records, so an exporter never sees a {@code flush()} overlap an
 * {@code export()} either. Requests are served as a batch — the cadence is at most one flush per requesting invocation
 * end, not exactly one — and a flush is preceded by the queued records a {@link #drain(String)} is waiting for, so a
 * burst of invocation ends is covered by one flush rather than one each. A request made while a flush runs waits for
 * the next turn. Exports are otherwise fire-and-forget; {@link #drain(String)} is called before an invocation returns
 * and waits for that execution's own latest record to reach every exporter.
 *
 * <p>Because there is one pump, that wait can also cover records other executions had already queued ahead of this one:
 * a drain is not isolated from the queue's head-of-line cost. What the per-execution keying guarantees is that another
 * execution's record can never <em>displace</em> this one — records coalesce only within their own execution, and a
 * drain cannot return until this execution's own latest record has reached every exporter.
 */
final class ExportScheduler {

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    /**
     * Upper bound on the passes {@link #drainAll()} makes over the outstanding executions. Only reached if new work
     * keeps arriving for as long as the drain runs; a normal drain settles in two passes.
     */
    private static final int MAX_DRAIN_ALL_PASSES = 1_000;

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
     * The thread serving the pump right now, or {@code null} while no pump is running. Deliberately <em>not</em>
     * guarded by {@code this}: it is read by {@link #flush()} and {@link #drain(String)} before they touch anything
     * else, and a lock acquisition there would put the pump's own monitor on the path of every invocation end.
     *
     * <p>Written only by the thread that enters {@link #pump} — a worker, or the caller's own thread on the
     * rejected-worker fallback, which is a case where the calling thread genuinely <em>is</em> the pump — and cleared
     * by that same thread on the way out, only if it is still the recorded one. A compare-and-set on the way out rather
     * than a blind clear: if some anomaly ever did leave two pumps running, the one that finishes first must not clear
     * the other, and must not leave a stale thread behind that a later, legitimate {@code flush()} from that same
     * thread would be mistaken for.
     */
    private final AtomicReference<Thread> pumpThread = new AtomicReference<>();

    /**
     * The latest record per execution ARN that the pump has not picked up yet, in the order the executions first queued
     * work. Guarded by {@code this}.
     */
    private final Map<String, WorkflowInsightRecord> pending = new LinkedHashMap<>();

    /**
     * Per-execution completion signal, present while that execution has work outstanding (pending or being exported
     * right now) and completed once its latest record has been handed to every exporter. Guarded by {@code this}.
     */
    private final Map<String, CompletableFuture<Void>> settled = new HashMap<>();

    /**
     * The execution ARNs whose record a pump has taken out of {@link #pending} and is handing to the exporters right
     * now. Guarded by {@code this}.
     *
     * <p>Without this, "no record in {@code pending}" is indistinguishable from "record already taken and mid-export",
     * and a pump running only its own {@code finally} would treat the second case as orphaned work and complete that
     * execution's drain signal while the record was still inside the exporters — exactly the early return
     * {@link #drain(String)} exists to prevent.
     */
    private final Set<String> exporting = new HashSet<>();

    /**
     * One entry per outstanding {@link #flush()} request, in request order, completed when a {@code flush()} that
     * started after that request was enqueued has reached every exporter. Guarded by {@code this}.
     *
     * <p>A queue of requests rather than a single flag: the pump takes the requests that are queued when its turn
     * begins and satisfies all of them with one flush, so concurrent invocation ends share a flush; a request enqueued
     * while that flush runs stays in the queue for the next turn, because a flush already in progress cannot be shown
     * to have seen the new requester's records.
     */
    private final Deque<CompletableFuture<Void>> flushRequests = new ArrayDeque<>();

    /**
     * How many {@link #drain(String)} calls are waiting for each execution right now. Guarded by {@code this}; an entry
     * is removed when its last waiter returns.
     *
     * <p>A record with a waiter is the last record of an invocation that cannot return until it is exported, so the
     * pump exports those records before it spends a flush fan-out. Without that, a burst of invocation ends is
     * serialized by the pump itself — one record exported per turn, a flush in between — and each end ends up paying
     * for its own flush, which is what coalescing is meant to prevent. Records nobody is waiting for (an
     * {@code ON_CHANGE} stream, say) are not front-loaded, so they cannot push a flush back either.
     */
    private final Map<String, Integer> drainWaiters = new HashMap<>();

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
     * Queues the latest record of one execution for export. If an export is already running, the record is held in that
     * execution's own slot (replacing only an earlier record of the <em>same</em> execution) and exported once the pump
     * reaches it.
     */
    void schedule(String executionArn, WorkflowInsightRecord record) {
        CompletableFuture<Void> handle;
        synchronized (this) {
            pending.put(executionArn, record);
            settled.computeIfAbsent(executionArn, arn -> new CompletableFuture<>());
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
     * Waits until the latest record of one execution has been handed to every exporter. Safe to call when that
     * execution has nothing outstanding. Used before the invocation returns to guarantee the final record is delivered.
     *
     * <p>The wait is for this execution's own latest record. Another execution's record can never displace it, so this
     * always returns having delivered this execution's latest snapshot; but since one pump exports serially, the wait
     * can also cover records other executions had already queued ahead of it.
     *
     * <p>While this waits, the execution is registered in {@link #drainWaiters}: it tells the pump that this record
     * gates an invocation return, so the pump exports it before spending a flush fan-out. See
     * {@link #exportRecordsADrainIsWaitingFor}.
     *
     * <p>Called from the pump thread itself, the wait is refused and reported instead of made: see
     * {@link #refuseWaitFromThePumpThread}.
     */
    void drain(String executionArn) {
        // Re-entered from the pump: this thread is the one that would settle the signal it is about to wait for. Refuse
        // and return; the record stays queued and this same pump exports it when it resumes its loop.
        if (refuseWaitFromThePumpThread("drain(executionArn)")) {
            return;
        }
        synchronized (this) {
            if (!settled.containsKey(executionArn)) {
                return;
            }
            drainWaiters.merge(executionArn, 1, Integer::sum);
        }
        try {
            drainUntilSettled(executionArn);
        } finally {
            synchronized (this) {
                drainWaiters.compute(
                        executionArn, (arn, waiting) -> waiting == null || waiting <= 1 ? null : waiting - 1);
            }
        }
    }

    private void drainUntilSettled(String executionArn) {
        while (true) {
            CompletableFuture<Void> signal;
            CompletableFuture<Void> handle;
            boolean runInline = false;
            synchronized (this) {
                signal = settled.get(executionArn);
                if (signal == null) {
                    return;
                }
                handle = inFlight;
                if (handle == null) {
                    // A record is outstanding with no pump running (a worker could not be started): export it here.
                    handle = new CompletableFuture<>();
                    inFlight = handle;
                    runInline = true;
                }
            }
            if (runInline) {
                pump(handle);
                continue;
            }
            // Wake either when this execution's record has been exported or when the current pump ends — the pump may
            // have ended without taking this record (a rejected worker), in which case the loop re-evaluates and
            // exports it inline.
            try {
                CompletableFuture.anyOf(signal, handle).join();
            } catch (Throwable t) {
                // Never spin on an unexpected wait failure, and never let it escape into the execution. Abandon this
                // execution's outstanding record instead of leaving it queued: WORKERS is a static, process-wide pool,
                // so a record left in pending here would be exported later by some unrelated execution's pump — out of
                // order, and after this invocation has already returned. Completing the signal also releases any other
                // drain waiting on the same execution rather than stranding it behind work nobody will do.
                abandon(executionArn);
                reportFailure(t);
                return;
            }
        }
    }

    /**
     * Flushes every exporter, serialized against exports: the request is queued and served by the pump between records,
     * so an exporter never sees {@code flush()} overlap {@code export()} — not even an export belonging to a different
     * execution running in the same environment. Returns once a flush that started after this request was enqueued has
     * reached every exporter.
     *
     * <p>Requests are coalesced: the pump takes every request queued at the start of its turn, exports any queued
     * record a {@link #drain(String)} is still waiting for, re-takes the requests those ends make as they are released,
     * and satisfies them all with one flush. Invocation ends that overlap therefore share a flush instead of paying for
     * one fan-out each. That is sound because a caller drains its own record before asking, so a flush that
     * <em>starts</em> after the request was enqueued has that record in the exporter's buffer. A request enqueued while
     * a flush is already running is never satisfied by it — it waits for the next turn.
     *
     * <p>A queue that never runs dry cannot starve a request either: the pump alternates one record and one batch of
     * requests, so a flush waits at most one export fan-out.
     *
     * <p>Called from the pump thread itself — which only something the pump invokes synchronously can do — the request
     * is refused and reported instead of made: see {@link #refuseWaitFromThePumpThread}.
     */
    void flush() {
        // Re-entered from the pump: this thread is the only one that could serve the request it is about to make, so it
        // must not make it. Refuse and return rather than enqueue a request nobody can serve.
        if (refuseWaitFromThePumpThread("flush()")) {
            return;
        }
        CompletableFuture<Void> request = new CompletableFuture<>();
        synchronized (this) {
            flushRequests.add(request);
        }
        while (true) {
            CompletableFuture<Void> handle;
            boolean startPump = false;
            synchronized (this) {
                if (request.isDone()) {
                    return;
                }
                handle = inFlight;
                if (handle == null) {
                    if (!flushRequests.contains(request)) {
                        // Liveness backstop: a pump took this request and unwound without serving it, which its
                        // `finally` is there to prevent. The request is no longer in the queue, so no future pump can
                        // find it — release the caller here instead of spinning up pumps that have nothing to do.
                        break;
                    }
                    // No pump is running (a worker could not be started earlier, or the pump went idle between the add
                    // above and this check): start one.
                    handle = new CompletableFuture<>();
                    inFlight = handle;
                    startPump = true;
                }
            }
            if (startPump) {
                CompletableFuture<Void> started = handle;
                try {
                    executor.execute(() -> pump(started));
                } catch (Throwable t) {
                    // No worker could be started. Serve the request on the calling thread, exactly as drain() exports a
                    // pending record inline: this pump owns `inFlight`, so no export can run beside it.
                    reportFailure(t);
                    pump(started);
                    continue;
                }
            }
            // Wake either when this request has been served or when the current pump ends — a pump can end without
            // serving it (a rejected worker), in which case the loop starts another one.
            try {
                CompletableFuture.anyOf(request, handle).join();
            } catch (Throwable t) {
                // Never spin on an unexpected wait failure, and never let it escape into the execution. Drop the
                // request rather than leaving it queued for some later, unrelated invocation's pump to serve.
                synchronized (this) {
                    flushRequests.remove(request);
                }
                reportFailure(t);
                break;
            }
        }
        request.complete(null);
    }

    /**
     * Waits for every execution's outstanding record. Test seam for a plugin-wide drain; the per-invocation path uses
     * {@link #drain(String)}.
     *
     * <p>Bounded by the number of passes, not by the set of ARNs seen: an execution that queues new work after it was
     * already drained must still be waited for (dropping it would silently weaken every assertion made after this
     * returns), while a producer that never stops cannot keep this spinning forever.
     */
    void drainAll() {
        // Every pass below is a drain(), and each one would be refused; without this the loop spends all of its passes
        // reporting the same refusal.
        if (refuseWaitFromThePumpThread("drainAll()")) {
            return;
        }
        for (int pass = 0; pass < MAX_DRAIN_ALL_PASSES; pass++) {
            List<String> outstanding;
            synchronized (this) {
                if (settled.isEmpty()) {
                    return;
                }
                outstanding = new ArrayList<>(settled.keySet());
            }
            for (String executionArn : outstanding) {
                drain(executionArn);
            }
        }
    }

    /** Gives up one execution's outstanding work: drops its queued record and releases every drain waiting on it. */
    private void abandon(String executionArn) {
        CompletableFuture<Void> signal;
        synchronized (this) {
            pending.remove(executionArn);
            signal = settled.remove(executionArn);
        }
        if (signal != null) {
            signal.complete(null);
        }
    }

    private void pump(CompletableFuture<Void> handle) {
        // Recorded for as long as this thread serves the pump — a worker, or a caller pumping inline after a rejected
        // worker — so that a flush() or drain() re-entered from anything the fan-out calls synchronously can tell that
        // it is asking itself. One atomic write per pump, and no lock: see the field.
        Thread self = Thread.currentThread();
        pumpThread.set(self);
        // The ARN this pump has taken and not settled yet. Only this pump may release it, so an abnormal unwind cannot
        // strand a drain, and no other pump can mistake it for orphaned work.
        String taken = null;
        // Likewise for the flush requests this pump has taken out of the queue and not completed yet.
        List<CompletableFuture<Void>> takenFlushes = null;
        try {
            // One record, then every flush request queued at that moment, alternating. Taking the record and returning
            // to idle both happen under the lock, so a record scheduled at any point is either exported by this pump or
            // starts the next one — never lost, and never displaced by another execution's record. A flush therefore
            // waits at most one fan-out (it cannot be starved by a queue that never runs dry) and still never overlaps
            // an export, because this loop runs them one after the other.
            //
            // A loop, deliberately, not a pump that re-enters itself to pick up the next item: written that way, one
            // frame per queued item accumulates until the stack overflows, and the rest of the queue is dropped.
            while (true) {
                String executionArn = null;
                WorkflowInsightRecord record = null;
                synchronized (this) {
                    Iterator<Map.Entry<String, WorkflowInsightRecord>> queued =
                            pending.entrySet().iterator();
                    if (!queued.hasNext() && flushRequests.isEmpty()) {
                        if (inFlight == handle) {
                            inFlight = null;
                        }
                        return;
                    }
                    if (queued.hasNext()) {
                        Map.Entry<String, WorkflowInsightRecord> next = queued.next();
                        queued.remove();
                        executionArn = next.getKey();
                        record = next.getValue();
                        // Marked under the same lock that removes the record, so the execution is never momentarily
                        // invisible to another pump's orphan check.
                        exporting.add(executionArn);
                    }
                }
                if (executionArn != null) {
                    taken = executionArn;
                    try {
                        exportToAll(record);
                    } finally {
                        signalSettled(executionArn);
                        taken = null;
                    }
                }
                // Taken only now that the fan-out above has settled, and taken as a batch: every request queued at this
                // instant is satisfied by the single flush below, so invocation ends that ask together cost one flush
                // rather than one each. Sound because each requester drained its own record before asking, so a flush
                // that starts after the request was enqueued already has that record in the exporter's buffer.
                //
                // Emptying the queue here — rather than after the flush — is what keeps a request that arrives while
                // that flush runs out of this batch: it lands in the now-empty queue and is served by the next turn,
                // never credited to a flush that was already in progress when it was made.
                synchronized (this) {
                    if (!flushRequests.isEmpty()) {
                        takenFlushes = new ArrayList<>(flushRequests);
                        flushRequests.clear();
                    }
                }
                if (takenFlushes != null) {
                    // Before spending the fan-out: export the queued records other invocations are still waiting on.
                    // Those ends cannot have asked for their flush yet — they are inside drain() — so without this the
                    // pump staggers them one record per turn, with a whole flush in between, and each pays for its own
                    // flush however aggressively the queue is coalesced.
                    exportRecordsADrainIsWaitingFor();
                    // Re-take: the ends released above ask for their flush now, and one flush covers all of them since
                    // it starts after every one of those records reached the exporters.
                    synchronized (this) {
                        if (!flushRequests.isEmpty()) {
                            takenFlushes.addAll(flushRequests);
                            flushRequests.clear();
                        }
                    }
                    try {
                        flushEveryExporter();
                    } finally {
                        // In `finally`: a Throwable from a customer's flush() — an Error, not just an exception — must
                        // never leave the invocations waiting on these requests parked forever.
                        completeAll(takenFlushes);
                        takenFlushes = null;
                    }
                }
            }
        } finally {
            // Before anything else, and before the handle below: whoever waits on these requests must be released even
            // if this pump is unwinding for a reason none of the guards above anticipated.
            if (takenFlushes != null) {
                completeAll(takenFlushes);
            }
            List<CompletableFuture<Void>> orphaned;
            synchronized (this) {
                if (inFlight == handle) {
                    inFlight = null;
                }
                if (taken != null) {
                    // Unwinding with a record still marked as being exported: this pump will never settle it, so
                    // release it here and let the orphan sweep below complete its drain.
                    exporting.remove(taken);
                }
                orphaned = takeSignalsWithNothingOutstanding();
            }
            // Defensive backstop: an execution with nothing outstanding — no record in the queue and none inside the
            // exporters — whose signal nevertheless survived must not leave a drain() waiting forever. No ordinary path
            // is known to produce that; it covers the unwinds that are hard to enumerate exhaustively rather than one
            // specific failure.
            for (CompletableFuture<Void> signal : orphaned) {
                signal.complete(null);
            }
            handle.complete(null);
            // Last, because everything above is still this pump's work and a flush() re-entered from any of it would
            // still have nobody to serve it. Conditional: a pump that recorded itself since must not be cleared here.
            pumpThread.compareAndSet(self, null);
        }
    }

    /**
     * Exports the queued records that a {@link #drain(String)} is waiting for, one at a time, and returns once they
     * have all reached the exporters. Called by the pump immediately before a flush.
     *
     * <p>Those records are the last records of invocations that cannot return until they are exported, and their ends
     * cannot ask for their flush until then. Exporting them first is therefore what lets one flush serve a whole burst
     * of invocation ends: without it the pump interleaves one record and one flush fan-out, and each end pays for a
     * flush of its own even though every request is coalesced.
     *
     * <p>Bounded by the snapshot taken under the lock, so a producer that keeps scheduling for an execution someone is
     * draining cannot hold a flush back indefinitely — and records nobody waits for are not exported here at all, so a
     * stream of {@code ON_CHANGE} snapshots still cannot starve a flush: it waits at most one ordinary fan-out plus
     * this pass over the executions whose invocation return is already blocked on their own record.
     */
    private void exportRecordsADrainIsWaitingFor() {
        List<String> awaited;
        synchronized (this) {
            if (pending.isEmpty() || drainWaiters.isEmpty()) {
                return;
            }
            awaited = new ArrayList<>();
            for (String executionArn : pending.keySet()) {
                if (drainWaiters.containsKey(executionArn)) {
                    awaited.add(executionArn);
                }
            }
        }
        for (String executionArn : awaited) {
            WorkflowInsightRecord record;
            synchronized (this) {
                record = pending.remove(executionArn);
                if (record != null) {
                    // Marked under the same lock that removes the record, exactly as the pump's own record step does,
                    // so the execution is never momentarily invisible to another pump's orphan check.
                    exporting.add(executionArn);
                }
            }
            if (record == null) {
                continue;
            }
            try {
                exportToAll(record);
            } finally {
                try {
                    signalSettled(executionArn);
                } catch (Throwable t) {
                    // Nothing here is expected to throw, but a record left marked as being exported would strand the
                    // drain that is waiting for it, so release it rather than leave the invocation parked.
                    synchronized (this) {
                        exporting.remove(executionArn);
                    }
                    reportFailure(t);
                }
            }
        }
    }

    /** Completes every taken flush request; one that cannot be completed must not stop the rest from being. */
    private void completeAll(List<CompletableFuture<Void>> requests) {
        for (CompletableFuture<Void> request : requests) {
            try {
                request.complete(null);
            } catch (Throwable t) {
                reportFailure(t);
            }
        }
    }

    /**
     * Completes one execution's drain signal now that its record has been exported, unless a newer record for the same
     * execution arrived meanwhile — that one settles the signal instead, so drain() always waits for the latest.
     */
    private void signalSettled(String executionArn) {
        CompletableFuture<Void> signal;
        synchronized (this) {
            if (pending.containsKey(executionArn)) {
                // A newer record is queued for the same execution. Leave the ARN marked as being exported: it is still
                // outstanding, and the export of that newer record settles the signal.
                return;
            }
            exporting.remove(executionArn);
            signal = settled.remove(executionArn);
        }
        if (signal != null) {
            signal.complete(null);
        }
    }

    /**
     * Removes and returns the signals of executions with nothing outstanding: no record queued <em>and</em> none being
     * handed to the exporters right now. Caller holds the lock.
     */
    private List<CompletableFuture<Void>> takeSignalsWithNothingOutstanding() {
        List<CompletableFuture<Void>> taken = new ArrayList<>();
        Iterator<Map.Entry<String, CompletableFuture<Void>>> signals =
                settled.entrySet().iterator();
        while (signals.hasNext()) {
            Map.Entry<String, CompletableFuture<Void>> signal = signals.next();
            if (!pending.containsKey(signal.getKey()) && !exporting.contains(signal.getKey())) {
                taken.add(signal.getValue());
                signals.remove();
            }
        }
        return taken;
    }

    /**
     * Flushes every exporter, each on its own worker, and waits for all of them to settle. A slow or failing flush on
     * one exporter never delays or fails the others. Plugin-wide, like the exporters themselves.
     *
     * <p>Private and called only from the pump: routing every flush through the pump is what keeps a {@code flush()}
     * from overlapping an {@code export()}, so this must not be reachable from outside. The per-exporter fan-out below
     * is parallelism <em>within</em> one flush, not concurrency with an export.
     */
    private void flushEveryExporter() {
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
        List<CompletableFuture<Void>> settledExporters = new ArrayList<>(exporters.size());
        for (InsightExporter exporter : exporters) {
            Runnable task = () -> runSafely(() -> action.accept(exporter));
            try {
                settledExporters.add(CompletableFuture.runAsync(task, executor));
            } catch (Throwable t) {
                reportFailure(t);
                task.run();
            }
        }
        for (CompletableFuture<Void> task : settledExporters) {
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

    /**
     * Reports and refuses a wait for the pump that was issued <em>from</em> the pump. Returns whether the caller is the
     * pump thread; when it is, the failure has already been reported and the caller must return without waiting.
     *
     * <p>Invariant: the thread that waits for the pump is never the thread that serves it. {@link #flush()} waits for a
     * request only a pump can complete, and {@link #drain(String)} waits for a signal only a pump can complete or for
     * the running pump's own handle. All three are satisfied by the pump between records.
     *
     * <p>Without this, a wait issued from the pump is a wait-for cycle one thread wide: the pump parks on the future it
     * would itself have completed, so it never reaches the point in its loop that completes it, and no other thread may
     * take over because {@code inFlight} is this pump's. The invocation never returns, and nothing reports it — a
     * {@link CompletableFuture} park cycle is not a monitor deadlock, so the JVM's deadlock detection cannot see it.
     * Reachable through anything the pump calls synchronously: with a single exporter the fan-out runs on the pump
     * thread, so a customer exporter's {@code export()} that asks for a flush, or a non-conforming {@code exportOne},
     * is enough. A conforming production {@code exportOne} does not re-enter the scheduler, so this is hardening.
     *
     * <p>So the call fails fast instead: the plugin's failure handler is told — it logs — and the caller returns as it
     * would from any other flush or drain, with nothing propagating into the execution. The queued work itself is not
     * dropped by refusing a {@code drain}: the record stays in {@code pending} and the pump asking the question is the
     * one that will export it. Callers that are not the pump — every SDK hook thread — never enter this branch and
     * behave exactly as before, and the check is a single volatile read, so no lock is added to that path.
     */
    private boolean refuseWaitFromThePumpThread(String call) {
        if (pumpThread.get() != Thread.currentThread()) {
            return false;
        }
        reportFailure(new IllegalStateException(call
                + " was called from the export pump thread, the only thread able to serve it; the call was refused"
                + " rather than deadlocking the invocation"));
        return true;
    }
}
