// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Serializes record exports so that, at most, one export runs at a time, while keeping the records of concurrently
 * running executions independent.
 *
 * <p>One scheduler serves the whole execution environment — the exporters it fans out to belong to the environment, and
 * an environment can host several durable executions at the same time (Lambda Managed Instances makes that routine).
 * The work, by contrast, is held <em>on the invocation it belongs to</em>: each {@link InsightPlugin} instance is one
 * invocation's plugin and owns that invocation's latest-record slot, drain signal, mid-export marker and drain-waiter
 * count. Coalescing happens only <em>within</em> one invocation, because the slot belongs to it.
 *
 * <p>That shape is not a tidying-up. The same facts once lived in five structures keyed by execution ARN — the plugin's
 * state map plus this class's {@code pending}, {@code settled}, {@code exporting} and {@code drainWaiters} — and two of
 * them could disagree about one execution. They did: "nothing queued for this ARN" was read as "nothing outstanding",
 * which is equally true of a record already taken and being exported, so an exiting pump completed another execution's
 * drain signal mid-export and that invocation returned before its record was delivered. Every fact now exists exactly
 * once, as a field of the one object the SDK gave that invocation, so the disagreement has no place to happen — and
 * since the SDK creates that object and drops it, the scheduler has no execution registry to keep in step with
 * anything.
 *
 * <p>Each {@link WorkflowInsightRecord} is a complete snapshot of its execution, so a newer record from the same
 * invocation fully supersedes one still waiting to be exported. While an export is in flight, additional updates from
 * that invocation are coalesced into its slot — intermediate records are dropped because the latest one already
 * contains all of their information. A record from a <em>different</em> invocation never displaces another's record.
 *
 * <p>The slot takes whichever record is handed to it last and compares nothing, so "newer" has to be established before
 * the hand-off. Customer code runs while a record is being built and can re-enter a hook of the same invocation, which
 * builds and hands over a newer record first; the build it re-entered from then hands over an older snapshot last.
 * {@code InsightPlugin}'s build revision identifies each build and {@link #scheduleIfNotSuperseded} drops a record
 * whose build has been overtaken, so the slot only ever advances.
 *
 * <p>A single pump exports the queued records one at a time, in the order the invocations first queued work
 * ({@link #queue}, which is ordering only — membership in it is the same fact as "this invocation has a record",
 * written in one place), so exporters still never see two exports at once and each record keeps its per-exporter
 * fan-out. {@link #flush()} requests are served by that same pump, between records, so an exporter never sees a
 * {@code flush()} overlap an {@code export()} either. Requests are served as a batch — the cadence is at most one flush
 * per requesting invocation end, not exactly one — and a flush is preceded by the queued records a drain is waiting
 * for, so a burst of invocation ends is covered by one flush rather than one each. A request made while a flush runs
 * waits for the next turn. Exports are otherwise fire-and-forget; {@link #drain(InsightPlugin)} is called before an
 * invocation returns and waits for that invocation's own latest record to reach every exporter.
 *
 * <p>Because there is one pump, that wait can also cover records other invocations had already queued ahead of this
 * one: a drain is not isolated from the queue's head-of-line cost. What per-invocation ownership guarantees is that
 * another invocation's record can never <em>displace</em> this one — records coalesce only within their own invocation,
 * and a drain cannot return until its own latest record has reached every exporter.
 */
final class ExportScheduler {

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    /**
     * Upper bound on the passes {@link #drainAll()} makes over the outstanding invocations. Only reached if new work
     * keeps arriving for as long as the drain runs; a normal drain settles in two passes.
     */
    private static final int MAX_DRAIN_ALL_PASSES = 1_000;

    /** How long one {@link #drainAll()} pass waits for a running pump before taking another pass. */
    private static final long PUMP_WAIT_MILLIS = 50;

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
     * guarded by {@code this}: it is read by {@link #flush()} and the drains before they touch anything else, and a
     * lock acquisition there would put the pump's own monitor on the path of every invocation end.
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
     * Marks the thread currently running one exporter's share of a fan-out for <em>this</em> scheduler, so that a
     * {@code flush()} or {@code drain()} re-entered from an exporter callback can tell that the pump is waiting for it.
     *
     * <p>With a single exporter the fan-out runs on the pump thread and {@link #pumpThread} already recognizes it. With
     * two or more, {@link #forEachExporterSettled} submits one task per exporter and the pump then joins them all, so
     * the callback runs on a thread that is not the pump but that the pump cannot outlive: a wait for the pump issued
     * from there is the same wait-for cycle, two threads wide instead of one. The pump parks in the join, so it never
     * reaches the point in its loop that would complete the future the worker is parked on.
     *
     * <p>An instance field rather than a static: a fan-out worker of one scheduler is not pump-dependent on any other
     * scheduler, and refusing its waits there would be a false positive. Set and cleared around each callback by the
     * thread that runs it, restoring whatever was there before rather than blindly removing, so a callback that the
     * pump ran inline (the rejected-worker fallback, where the fan-out thread <em>is</em> the pump) cannot clear a mark
     * an enclosing frame still needs.
     */
    private final ThreadLocal<Boolean> exporterFanOutThread = new ThreadLocal<>();

    /**
     * The invocations with a record no pump has picked up yet, in the order they first queued work. Ordering only — the
     * record itself lives on the invocation's plugin instance. Guarded by {@code this}.
     *
     * <p>This is the only collection of per-invocation objects the scheduler has, and it holds an instance for exactly
     * as long as that instance has a record waiting: nothing here has to be cleaned up at an invocation boundary, and
     * an instance the SDK has dropped is unreachable from the scheduler the moment its last record is taken.
     *
     * <p>Invariant, and the only thing that could still be said twice: an invocation is in here exactly while its
     * {@link InsightPlugin#record} is non-null. Every record moves through {@link #queueRecord}, {@link #takeRecord} or
     * {@link #dropRecord}, which write both halves together, and a set makes a double entry impossible by construction.
     */
    private final Set<InsightPlugin> queue = new LinkedHashSet<>();

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

    // --- Scheduling. ---

    /**
     * Queues the latest record of one invocation for export, with no ordering check. If an export is already running,
     * the record is held in that invocation's own slot (replacing only an earlier record of the <em>same</em>
     * invocation) and exported once the pump reaches it.
     *
     * <p>The slot takes whichever record is handed over last and does not compare record ages, so this is the right
     * entry point only for a record that cannot be superseded. The plugin's RUNNING records go through
     * {@link #scheduleIfNotSuperseded} and its final record through {@link #closeAndSchedule}; both add the ordering
     * checks this one omits.
     */
    void schedule(InsightPlugin execution, WorkflowInsightRecord record) {
        CompletableFuture<Void> handle;
        synchronized (this) {
            queueRecord(execution, record);
            handle = claimPumpIfIdle();
        }
        startPump(handle);
    }

    /**
     * Schedules a non-terminal record unless it has been superseded, which is two separate facts.
     *
     * <p>The invocation may already have ended. No RUNNING snapshot may follow the final record, so
     * {@link InsightPlugin#closed} rejects it.
     *
     * <p>A newer build of this same invocation may already have started. Customer code runs inside a build — the
     * content transforms, an operation result transform, a serializer for a customer type — and can re-enter a hook, so
     * the build that hands its record over last is not necessarily the build that started last. Without the revision
     * check the slot would take that older snapshot and the newer one would be lost, or, if a pump had already taken
     * the newer one, an exporter would see the older snapshot after the newer one.
     *
     * <p>The superseded record is dropped rather than queued. Nothing is lost: a record is a complete snapshot of one
     * execution, so the record that superseded it carries everything it carries. That is the same property that makes
     * the slot's coalescing sound.
     *
     * <p>Both checks and the hand-off are one critical section, on the monitor that owns both fields, so a record
     * cannot pass the checks and then be queued after the record that supersedes it.
     *
     * @param buildRevision the revision the caller took before it started building this record
     * @return whether the record was queued
     */
    boolean scheduleIfNotSuperseded(InsightPlugin execution, WorkflowInsightRecord record, long buildRevision) {
        CompletableFuture<Void> handle;
        synchronized (this) {
            if (execution.closed || !execution.isNewestBuild(buildRevision)) {
                return false;
            }
            queueRecord(execution, record);
            handle = claimPumpIfIdle();
        }
        startPump(handle);
        return true;
    }

    /**
     * Marks the invocation ended and, when given a record, schedules it as the last one for that invocation.
     *
     * <p>The final record is queued without the build-revision check {@link #scheduleIfNotSuperseded} makes. Customer
     * code running inside the final record's build can start a newer RUNNING build, which would leave the final
     * record's revision stale, and a checked hand-off would then drop it and leave a RUNNING snapshot as the
     * execution's last exported state. Exempting it cannot let a stale record win, because {@code closed} is set in
     * this same critical section and every RUNNING record handed over afterwards is rejected.
     */
    void closeAndSchedule(InsightPlugin execution, WorkflowInsightRecord finalRecord) {
        if (finalRecord == null && execution.closed) {
            // The idempotent second call from the hook's `finally`. A volatile read, so the common case of an
            // invocation end that already scheduled its final record does not take the lock again.
            return;
        }
        CompletableFuture<Void> handle = null;
        synchronized (this) {
            execution.closed = true;
            if (finalRecord != null) {
                queueRecord(execution, finalRecord);
                handle = claimPumpIfIdle();
            }
        }
        startPump(handle);
    }

    /**
     * Claims the pump for the caller when none is running, returning the handle to run with, or {@code null} when a
     * pump already owns the scheduler and will pick the work up. Caller holds the lock.
     */
    private CompletableFuture<Void> claimPumpIfIdle() {
        if (inFlight != null) {
            return null;
        }
        CompletableFuture<Void> handle = new CompletableFuture<>();
        inFlight = handle;
        return handle;
    }

    /** Starts a claimed pump on a worker; a no-op when the caller claimed nothing. */
    private void startPump(CompletableFuture<Void> handle) {
        if (handle == null) {
            return;
        }
        try {
            executor.execute(() -> pump(handle));
        } catch (Throwable t) {
            // No worker could be started. Keep the queued record and return to idle so a later schedule() retries, and
            // a drain runs whatever is still queued on the calling thread before the invocation returns. Complete the
            // handle too: a drain that already observed it must wake up and take that inline path.
            synchronized (this) {
                if (inFlight == handle) {
                    inFlight = null;
                }
            }
            handle.complete(null);
            reportFailure(t);
        }
    }

    // --- The record slot: the two halves of "this invocation has a record queued", always written together. ---

    /** Puts this invocation's latest record in its slot and makes sure it has a drain signal. Caller holds the lock. */
    private void queueRecord(InsightPlugin execution, WorkflowInsightRecord record) {
        execution.record = record;
        queue.add(execution);
        if (execution.settled == null) {
            execution.settled = new CompletableFuture<>();
        }
    }

    /**
     * Takes this invocation's queued record for export and marks it mid-export. Caller holds the lock and has checked
     * that a record is there.
     *
     * <p>The marking is not a separate step in a separate structure: leaving the slot and becoming "inside the
     * exporters" are one write of one object, so no reader can see the invocation between the two and conclude it has
     * nothing outstanding.
     */
    private WorkflowInsightRecord takeRecord(InsightPlugin execution) {
        WorkflowInsightRecord record = execution.record;
        execution.record = null;
        queue.remove(execution);
        execution.exporting = true;
        return record;
    }

    /** Drops this invocation's queued record without exporting it. Caller holds the lock. */
    private void dropRecord(InsightPlugin execution) {
        execution.record = null;
        queue.remove(execution);
    }

    // --- Draining. ---

    /**
     * Waits until the latest record of one invocation has been handed to every exporter. Safe to call when that
     * invocation has nothing outstanding. Used before the invocation returns to guarantee the final record is
     * delivered.
     *
     * <p>The wait is for that invocation's own latest record. Another invocation's record can never displace it, so
     * this always returns having delivered this invocation's latest snapshot; but since one pump exports serially, the
     * wait can also cover records other invocations had already queued ahead of it.
     *
     * <p>While this waits, the invocation counts a drain waiter — on the instance itself, so the count cannot come to
     * describe a different one. It tells the pump that this record gates an invocation return, so the pump exports it
     * before spending a flush fan-out. See {@link #exportRecordsADrainIsWaitingFor}.
     *
     * <p>Called from the pump thread itself, or from an exporter fan-out worker that pump is waiting for, the wait is
     * refused and reported instead of made: see {@link #refuseWaitThatWouldBlockThePump}.
     */
    void drain(InsightPlugin execution) {
        // Re-entered from a thread the pump's progress depends on: waiting here would park on a signal only that pump
        // can settle. Refuse and return; the record stays queued and that same pump exports it once it resumes its
        // loop.
        if (refuseWaitThatWouldBlockThePump("drain(execution)")) {
            return;
        }
        synchronized (this) {
            if (execution.settled == null) {
                return;
            }
            execution.drainWaiters++;
        }
        try {
            drainUntilSettled(execution);
        } finally {
            synchronized (this) {
                execution.drainWaiters--;
            }
        }
    }

    private void drainUntilSettled(InsightPlugin execution) {
        while (true) {
            CompletableFuture<Void> signal;
            CompletableFuture<Void> handle;
            boolean runInline = false;
            synchronized (this) {
                signal = execution.settled;
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
                if (nothingCanSettle(execution, signal)) {
                    // The pump this thread just ran found no record for this invocation and left none inside the
                    // exporters, yet the signal survives: no later step can complete it, so waiting again would only
                    // start empty pumps forever. Release it here and report — the pump's own exit does not sweep for
                    // this any more, because it has no registry of invocations to sweep and does not need one: the
                    // thread that would be stranded is this one, and it holds the instance.
                    abandon(execution);
                    reportFailure(new IllegalStateException(
                            "a drain signal survived a pump that had nothing to export for it; the drain was released"
                                    + " rather than waiting for work nobody will do"));
                    return;
                }
                continue;
            }
            // Wake either when this invocation's record has been exported or when the current pump ends — the pump may
            // have ended without taking this record (a rejected worker), in which case the loop re-evaluates and
            // exports it inline.
            try {
                CompletableFuture.anyOf(signal, handle).join();
            } catch (Throwable t) {
                // Never spin on an unexpected wait failure, and never let it escape into the execution. Abandon this
                // invocation's outstanding record instead of leaving it queued: WORKERS is a static, process-wide pool,
                // so a record left queued here would be exported later by some unrelated execution's pump — out of
                // order, and after this invocation has already returned. Completing the signal also releases any other
                // drain waiting on the same invocation rather than stranding it behind work nobody will do.
                abandon(execution);
                reportFailure(t);
                return;
            }
        }
    }

    /**
     * True when this invocation still holds the same drain signal but has no queued record and none inside the
     * exporters, so nothing that could complete the signal is left. No ordinary path produces that; this is the
     * liveness backstop for the unwinds that are hard to enumerate exhaustively.
     */
    private synchronized boolean nothingCanSettle(InsightPlugin execution, CompletableFuture<Void> signal) {
        return execution.settled == signal && execution.record == null && !execution.exporting;
    }

    /**
     * Waits for every outstanding record. Test seam for an environment-wide drain; the per-invocation path uses
     * {@link #drain(InsightPlugin)}.
     *
     * <p>Returns once nothing is queued and no pump owns the scheduler, which is exactly "every record scheduled so far
     * has reached the exporters": a pump only returns to idle with its queue empty and the record it took settled.
     *
     * <p>Bounded by the number of passes, not by the set of invocations seen: an invocation that queues new work after
     * it was already drained must still be waited for (dropping it would silently weaken every assertion made after
     * this returns), while a producer that never stops cannot keep this spinning forever.
     */
    void drainAll() {
        // Every pass below is a drain, and each one would be refused; without this the loop spends all of its passes
        // reporting the same refusal.
        if (refuseWaitThatWouldBlockThePump("drainAll()")) {
            return;
        }
        for (int pass = 0; pass < MAX_DRAIN_ALL_PASSES; pass++) {
            List<InsightPlugin> outstanding;
            CompletableFuture<Void> handle;
            synchronized (this) {
                outstanding = new ArrayList<>(queue);
                handle = inFlight;
                if (outstanding.isEmpty() && handle == null) {
                    return;
                }
            }
            for (InsightPlugin execution : outstanding) {
                drain(execution);
            }
            if (outstanding.isEmpty()) {
                // Nothing is queued for anyone, but a pump still owns the scheduler: it may be inside an exporter with
                // a
                // record whose only reference is its own local variable, and with no registry of invocations there is
                // no
                // way to name that record and drain it. Waiting for the pump itself covers it — in bounded steps, so a
                // producer that keeps the pump permanently busy cannot make this unbounded, and so the wait is a real
                // wait rather than a re-poll.
                try {
                    handle.get(PUMP_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // Still running; take another pass.
                } catch (Throwable t) {
                    reportFailure(t);
                    return;
                }
            }
        }
    }

    /**
     * Test seam: how many invocations the scheduler still holds a reference to.
     *
     * <p>{@link #queue} is the only collection of per-invocation objects the scheduler has, so this is the whole of the
     * per-invocation state the environment retains. Zero means the environment — which outlives every invocation —
     * holds nothing belonging to any invocation it has served.
     */
    synchronized int retainedInvocationCount() {
        return queue.size();
    }

    /** Test seam: whether the scheduler still holds a reference to one particular invocation. */
    synchronized boolean retains(InsightPlugin execution) {
        return queue.contains(execution);
    }

    /** Gives up one invocation's outstanding work: drops its queued record and releases every drain waiting on it. */
    private void abandon(InsightPlugin execution) {
        CompletableFuture<Void> signal;
        synchronized (this) {
            dropRecord(execution);
            execution.exporting = false;
            signal = execution.settled;
            execution.settled = null;
        }
        if (signal != null) {
            signal.complete(null);
        }
    }

    // --- The pump. ---

    private void pump(CompletableFuture<Void> handle) {
        // Recorded for as long as this thread serves the pump — a worker, or a caller pumping inline after a rejected
        // worker — so that a flush() or drain() re-entered from anything the fan-out calls synchronously can tell that
        // it is asking itself. One atomic write per pump, and no lock: see the field.
        Thread self = Thread.currentThread();
        pumpThread.set(self);
        // The invocation this pump has taken a record from and not settled yet. Only this pump may release it, so an
        // abnormal unwind cannot strand a drain, and no other pump can mistake it for orphaned work.
        InsightPlugin taken = null;
        // Likewise for the flush requests this pump has taken out of the queue and not completed yet.
        List<CompletableFuture<Void>> takenFlushes = null;
        try {
            // One record, then every flush request queued at that moment, alternating. Taking the record and returning
            // to idle both happen under the lock, so a record scheduled at any point is either exported by this pump or
            // starts the next one — never lost, and never displaced by another invocation's record. A flush therefore
            // waits at most one fan-out (it cannot be starved by a queue that never runs dry) and still never overlaps
            // an export, because this loop runs them one after the other.
            //
            // A loop, deliberately, not a pump that re-enters itself to pick up the next item: written that way, one
            // frame per queued item accumulates until the stack overflows, and the rest of the queue is dropped.
            while (true) {
                InsightPlugin next = null;
                WorkflowInsightRecord record = null;
                synchronized (this) {
                    if (queue.isEmpty() && flushRequests.isEmpty()) {
                        if (inFlight == handle) {
                            inFlight = null;
                        }
                        return;
                    }
                    if (!queue.isEmpty()) {
                        next = queue.iterator().next();
                        // Leaving the slot and being marked mid-export are one write of one object, so the invocation
                        // is
                        // never momentarily indistinguishable from one with nothing outstanding.
                        record = takeRecord(next);
                    }
                }
                if (next != null) {
                    taken = next;
                    try {
                        exportToAll(record);
                    } finally {
                        signalSettled(next);
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
                    // Those ends cannot have asked for their flush yet — they are inside a drain — so without this the
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
            CompletableFuture<Void> orphaned = null;
            synchronized (this) {
                if (inFlight == handle) {
                    inFlight = null;
                }
                if (taken != null) {
                    // Unwinding with a record still marked as being exported: this pump will never settle it. Release
                    // the marker and, unless a newer record for the same invocation is queued for a later pump to
                    // export, complete the drain waiting on it — the instance is right here, so no sweep over other
                    // invocations is needed to find it.
                    taken.exporting = false;
                    if (taken.record == null) {
                        orphaned = taken.settled;
                        taken.settled = null;
                    }
                }
            }
            if (orphaned != null) {
                orphaned.complete(null);
            }
            handle.complete(null);
            // Last, because everything above is still this pump's work and a flush() re-entered from any of it would
            // still have nobody to serve it. Conditional: a pump that recorded itself since must not be cleared here.
            pumpThread.compareAndSet(self, null);
        }
    }

    /**
     * Exports the queued records that a drain is waiting for, one at a time, and returns once they have all reached the
     * exporters. Called by the pump immediately before a flush.
     *
     * <p>Those records are the last records of invocations that cannot return until they are exported, and their ends
     * cannot ask for their flush until then. Exporting them first is therefore what lets one flush serve a whole burst
     * of invocation ends: without it the pump interleaves one record and one flush fan-out, and each end pays for a
     * flush of its own even though every request is coalesced.
     *
     * <p>Bounded by the snapshot taken under the lock, so a producer that keeps scheduling for an invocation someone is
     * draining cannot hold a flush back indefinitely — and records nobody waits for are not exported here at all, so a
     * stream of {@code ON_CHANGE} snapshots still cannot starve a flush: it waits at most one ordinary fan-out plus
     * this pass over the invocations whose return is already blocked on their own record.
     */
    private void exportRecordsADrainIsWaitingFor() {
        List<InsightPlugin> awaited = null;
        synchronized (this) {
            for (InsightPlugin execution : queue) {
                if (execution.drainWaiters > 0) {
                    if (awaited == null) {
                        awaited = new ArrayList<>();
                    }
                    awaited.add(execution);
                }
            }
        }
        if (awaited == null) {
            return;
        }
        for (InsightPlugin execution : awaited) {
            WorkflowInsightRecord record;
            synchronized (this) {
                record = execution.record == null ? null : takeRecord(execution);
            }
            if (record == null) {
                continue;
            }
            try {
                exportToAll(record);
            } finally {
                try {
                    signalSettled(execution);
                } catch (Throwable t) {
                    // Nothing here is expected to throw, but a record left marked as being exported would strand the
                    // drain that is waiting for it, so release it and that drain rather than leave the invocation
                    // parked.
                    abandon(execution);
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
     * Completes one invocation's drain signal now that its record has been exported, unless a newer record from the
     * same invocation arrived meanwhile — that one settles the signal instead, so a drain always waits for the latest.
     */
    private void signalSettled(InsightPlugin execution) {
        CompletableFuture<Void> signal;
        synchronized (this) {
            if (execution.record != null) {
                // A newer record is queued for the same invocation. Leave it marked as being exported: it is still
                // outstanding, and the export of that newer record settles the signal.
                return;
            }
            execution.exporting = false;
            signal = execution.settled;
            execution.settled = null;
        }
        if (signal != null) {
            signal.complete(null);
        }
    }

    // --- Flushing. ---

    /**
     * Flushes every exporter, serialized against exports: the request is queued and served by the pump between records,
     * so an exporter never sees {@code flush()} overlap {@code export()} — not even an export belonging to a different
     * execution running in the same environment. Returns once a flush that started after this request was enqueued has
     * reached every exporter.
     *
     * <p>Requests are coalesced: the pump takes every request queued at the start of its turn, exports any queued
     * record a drain is still waiting for, re-takes the requests those ends make as they are released, and satisfies
     * them all with one flush. Invocation ends that overlap therefore share a flush instead of paying for one fan-out
     * each. That is sound because a caller drains its own record before asking, so a flush that <em>starts</em> after
     * the request was enqueued has that record in the exporter's buffer. A request enqueued while a flush is already
     * running is never satisfied by it — it waits for the next turn.
     *
     * <p>A queue that never runs dry cannot starve a request either: the pump alternates one record and one batch of
     * requests, so a flush waits at most one export fan-out.
     *
     * <p>Called from the pump thread itself — or from an exporter fan-out worker that pump is waiting for, which is
     * what a callback re-entering the scheduler does when two or more exporters are configured — the request is refused
     * and reported instead of made: see {@link #refuseWaitThatWouldBlockThePump}.
     */
    void flush() {
        // Re-entered from a thread the pump's progress depends on: the pump is the only thread that could serve the
        // request, and it cannot while this caller has not returned. Refuse rather than enqueue a request nobody
        // serves.
        if (refuseWaitThatWouldBlockThePump("flush()")) {
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
                    // No worker could be started. Serve the request on the calling thread, exactly as a drain exports a
                    // queued record inline: this pump owns `inFlight`, so no export can run beside it.
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
     * Flushes every exporter, each on its own worker, and waits for all of them to settle. A slow or failing flush on
     * one exporter never delays or fails the others. Environment-wide, like the exporters themselves.
     *
     * <p>Private and called only from the pump: routing every flush through the pump is what keeps a {@code flush()}
     * from overlapping an {@code export()}, so this must not be reachable from outside. The per-exporter fan-out below
     * is parallelism <em>within</em> one flush, not concurrency with an export.
     */
    private void flushEveryExporter() {
        forEachExporterSettled(InsightExporter::flush);
    }

    // --- Exporting. ---

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
            // On the pump thread itself, which the pump-thread check already refuses waits from.
            runSafely(() -> action.accept(exporters.get(0)));
            return;
        }
        List<CompletableFuture<Void>> settledExporters = new ArrayList<>(exporters.size());
        for (InsightExporter exporter : exporters) {
            // Marked as a fan-out task: the pump joins every one of these below, so a wait for the pump issued from
            // inside one must be refused exactly as one issued from the pump itself.
            Runnable task = () -> runSafely(() -> runAsExporterFanOut(() -> action.accept(exporter)));
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

    /**
     * Runs one exporter's share of a fan-out with this thread marked pump-dependent, restoring the previous mark on the
     * way out. The mark is what makes {@link #refuseWaitThatWouldBlockThePump} recognize a fan-out worker.
     */
    private void runAsExporterFanOut(Runnable action) {
        Boolean previous = exporterFanOutThread.get();
        exporterFanOutThread.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            if (previous == null) {
                // Removed rather than set back to null: these run on a shared, process-wide pool, so a thread must not
                // keep an entry for this scheduler after its task ends.
                exporterFanOutThread.remove();
            } else {
                exporterFanOutThread.set(previous);
            }
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
     * Reports and refuses a wait for the pump that was issued from a thread the pump's own progress depends on. Returns
     * whether the caller is such a thread; when it is, the failure has already been reported and the caller must return
     * without waiting.
     *
     * <p>Invariant: the thread that waits for the pump is never a thread the pump waits for. {@link #flush()} waits for
     * a request only a pump can complete, and a drain waits for a signal only a pump can complete or for the running
     * pump's own handle. All three are satisfied by the pump between records.
     *
     * <p>Two threads qualify. The pump thread itself: a wait issued from there is a wait-for cycle one thread wide —
     * the pump parks on the future it would itself have completed, so it never reaches the point in its loop that
     * completes it, and no other thread may take over because {@code inFlight} is this pump's. And an exporter fan-out
     * worker: with two or more exporters the pump submits one task per exporter and joins them all, so a wait issued
     * from a callback running on one of those workers is the same cycle two threads wide — the worker parks on a future
     * only the pump can complete, and the pump is parked in the join waiting for that worker. Neither is a monitor
     * deadlock, so the JVM's deadlock detection cannot see either one, and the invocation simply never returns.
     *
     * <p>Reachable through anything a fan-out calls synchronously: with a single exporter the fan-out runs on the pump
     * thread, so a customer exporter's {@code export()} or {@code flush()} that asks the scheduler for a flush, or a
     * non-conforming {@code exportOne}, is enough; with several it runs on a worker instead, and the same call is
     * refused for the same reason. A conforming production {@code exportOne} does not re-enter the scheduler, so this
     * is hardening.
     *
     * <p>So the call fails fast instead: the plugin's failure handler is told — it logs — and the caller returns as it
     * would from any other flush or drain, with nothing propagating into the execution. The queued work itself is not
     * dropped by refusing a drain: the record stays in the invocation's slot, and the pump that is waiting for this
     * caller exports it as soon as this caller returns and the fan-out it belongs to settles. Callers that are neither
     * — every SDK hook thread — never enter this branch and behave exactly as before; the check is a volatile read plus
     * a thread-local read, so no lock is added to that path.
     */
    private boolean refuseWaitThatWouldBlockThePump(String call) {
        if (pumpThread.get() == Thread.currentThread()) {
            reportFailure(new IllegalStateException(call
                    + " was called from the export pump thread, the only thread able to serve it; the call was refused"
                    + " rather than deadlocking the invocation"));
            return true;
        }
        if (Boolean.TRUE.equals(exporterFanOutThread.get())) {
            reportFailure(new IllegalStateException(call
                    + " was called from an exporter fan-out worker the export pump is waiting for, so the pump cannot"
                    + " serve it; the call was refused rather than deadlocking the invocation"));
            return true;
        }
        return false;
    }
}
