// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * The Workflow Insight plugin instance for one Lambda invocation: both the state that invocation's records are built
 * from and the slot the {@link ExportScheduler} exports them through.
 *
 * <p>The SDK creates one of these per invocation, from the {@link InvocationInfo} it is about to hand the first hook,
 * and drops it when the invocation returns. So everything about an execution is a plain field here — the parsed ARN,
 * the stable start time, the one-time sampling decision, the detached input snapshot, the latest queued record, the
 * drain signal, the mid-export marker and the drain-waiter count. There is nothing to key by execution ARN and nothing
 * to register or release: an instance <em>is</em> the registration, and its lifetime is the invocation's.
 *
 * <p>Two objects outlive the invocation and are shared by every instance the factory creates: the resolved
 * {@link InsightSettings} and the {@link ExportScheduler}. The scheduler is shared on purpose — serializing exports is
 * a property of the exporters, which belong to the environment, not to one invocation.
 *
 * <p><b>Ownership.</b> Three groups of fields:
 *
 * <ul>
 *   <li><em>Identity</em> — {@link #executionArn}, {@link #arn}, {@link #startTime}, {@link #sampledIn} — is taken from
 *       the {@link InvocationInfo} the factory receives and is {@code final}. It cannot be observed half-built, and
 *       there is no second invocation that could change it.
 *   <li><em>The input snapshot</em> — {@link #cachedInput} — is written by the thread that fires
 *       {@code onInvocationStart} and read by the operation-change and invocation-end threads of the same invocation,
 *       which the SDK does not promise are the same thread; {@code volatile} for that publication.
 *   <li><em>The build revision</em> — {@link #buildRevision} — counts the record builds this invocation has started, so
 *       that a build which was overtaken can be recognized at hand-off time and its record dropped. Atomic rather than
 *       {@code volatile}, because the case it exists for is two builds running at once. See the field.
 *   <li><em>Scheduling state</em> — {@link #record}, {@link #settled}, {@link #exporting}, {@link #drainWaiters} and
 *       {@link #closed} — is shared with the export pump and guarded by the monitor of {@link #scheduler}. One monitor
 *       for the whole environment, not one per invocation, so the {@code closed} check and the hand-off of a record are
 *       a single critical section and there is no lock ordering between instances to get wrong.
 * </ul>
 *
 * <p>{@link #closed} is additionally {@code volatile}: the hook threads read it without the lock as a fast pre-check.
 * That read only ever skips work — the authoritative check is made under the monitor by
 * {@link ExportScheduler#scheduleIfOpen}. It is written once, from false to true, and never back: an execution that
 * suspends and resumes gets a new instance rather than a reset one.
 */
final class InsightPlugin implements DurableExecutionPlugin {

    /** Resolved configuration, shared by every instance of the environment. */
    private final InsightSettings settings;

    /**
     * Shared with every other instance: exports are serialized across the whole environment. Package-private because it
     * is the monitor this instance's scheduling fields are guarded by, which the tests in this package hold when they
     * read them.
     */
    final ExportScheduler scheduler;

    // --- Identity, from the InvocationInfo the factory was called with. ---

    /** The execution this instance observes. */
    final String executionArn;

    /** The parsed execution ARN, parsed once for every record this instance builds. */
    final ArnParser arn;

    /** Stable execution start time, from {@code InvocationInfo.executionStartTime()}. */
    final Instant startTime;

    /** The one-time sampling decision; deterministic in the ARN, so a resumed invocation decides the same way. */
    final boolean sampledIn;

    // --- The input snapshot. ---

    /**
     * Detached snapshot of the execution input, the single source of truth for {@code input} on every emission of this
     * invocation. Written by {@code onInvocationStart}, read by every later build.
     */
    private volatile Object cachedInput;

    // --- Build ordering. ---

    /**
     * Counts the record builds this invocation has started. The value a build takes identifies that build.
     *
     * <p>Customer code runs inside a build, on the hook thread: the input and output content transforms, an operation's
     * result transform, and any Jackson serializer registered for a customer type. That code can call back into a hook
     * of this same instance, and it runs before anything is scheduled, so a build can be overtaken by a newer build
     * that starts and finishes inside it. Two hook threads for one invocation would produce the same overlap.
     *
     * <p>The scheduler's slot holds one record per invocation and takes whichever record is handed to it last, with no
     * comparison of age. An overtaken build would therefore write its older snapshot over the newer one. Every build
     * takes the next value here before it starts, and the scheduler queues the record only while that value is still
     * the newest, so an overtaken build's record is dropped instead.
     *
     * <p>An {@link AtomicLong} rather than a {@code volatile long}: {@code ++} on a {@code volatile long} is a
     * read-modify-write, so two concurrent builds can take the same value and each conclude its own record is the
     * newest. That is the very case the check exists for, so a racy counter would guard nothing.
     */
    private final AtomicLong buildRevision = new AtomicLong();

    // --- Scheduling state: guarded by the scheduler's monitor. ---

    /**
     * The latest record for this invocation that no pump has picked up yet, or {@code null} when none is queued.
     *
     * <p>A newer record replaces an older one here — each record is a complete snapshot, so the older one carries
     * nothing the newer one lacks. That is the whole of coalescing: one slot, on the instance, which no other
     * invocation can reach.
     *
     * <p>Which record is newer is decided by {@link #buildRevision}, not by the order the records reach this slot. The
     * slot itself takes the last hand-off unconditionally, and the last hand-off is not the newest build when a build
     * was overtaken by one that customer code started from inside it.
     */
    WorkflowInsightRecord record;

    /**
     * Completes once this invocation's latest record has been handed to every exporter; {@code null} when nothing is
     * outstanding.
     */
    CompletableFuture<Void> settled;

    /**
     * Whether a pump has taken this invocation's record and is handing it to the exporters right now.
     *
     * <p>Set and cleared in the same critical sections that move {@link #record}, so "no queued record" is never
     * mistaken for "nothing outstanding" while the record is inside the exporters.
     */
    boolean exporting;

    /**
     * How many {@code drain} calls are waiting for this invocation right now.
     *
     * <p>A record with a waiter gates an invocation return, so the pump exports it before it spends a flush fan-out.
     */
    int drainWaiters;

    /**
     * Set once invocation end begins; never cleared. Guarded by the scheduler's monitor — the same monitor that queues
     * the record, so the check and the hand-off are one critical section — and {@code volatile} for the hook-side
     * pre-check.
     *
     * <p>A checkpoint that completes while the end record is being drained still delivers an operation-change hook to
     * this same instance, and that RUNNING snapshot must not supersede the final record.
     *
     * <p>This orders RUNNING records against the final record; {@link #buildRevision} orders RUNNING records against
     * each other. Neither covers the other's case. A boolean cannot say which of two RUNNING builds is newer, and the
     * revision cannot reject a RUNNING record that follows the final one, because the final record is queued without a
     * revision check. See {@link ExportScheduler#closeAndSchedule}.
     */
    volatile boolean closed;

    /**
     * Creates the instance that serves one invocation. Identity comes from {@code info} rather than from the first
     * hook, so every field a record is keyed by exists before any hook can fire.
     *
     * @throws RuntimeException if the invocation has no usable execution ARN; the SDK contains that exactly as it
     *     contains a hook failure, by skipping this plugin for the invocation
     */
    InsightPlugin(InsightSettings settings, ExportScheduler scheduler, InvocationInfo info) {
        this.settings = settings;
        this.scheduler = scheduler;
        this.executionArn = info.durableExecutionArn();
        this.arn = ArnParser.parse(executionArn);
        this.startTime = info.executionStartTime();
        this.sampledIn = WorkflowInsight.shouldSample(executionArn, settings.samplingRate);
    }

    /** Test seam: waits until every scheduled record has been handed to the exporters. */
    void drainExports() {
        scheduler.drainAll();
    }

    @Override
    public void onInvocationStart(InvocationInfo info) {
        try {
            if (!sampledIn) {
                return;
            }
            // Detach the execution input from the live handler value immediately, before the user handler or any
            // content transform can mutate it. This raw, detached snapshot is the single source of truth for input
            // on every emission (start / change / end); each build hands transforms a separate defensive copy so a
            // mutating transform cannot corrupt it. Guard the snapshot: a Throwable here (e.g. a payload whose
            // serialization overflows the stack) must omit the captured input, never fail the user handler.
            try {
                cachedInput = Json.deepCopyContent(info.executionInput());
            } catch (Throwable t) {
                WorkflowInsight.logSafely("failed to snapshot execution input; omitting input", t);
                cachedInput = null;
            }
            if (settings.emitMode == WorkflowInsightConfig.EmitMode.ON_CHANGE) {
                // The revision is taken before the build, never after. Customer code runs inside buildRecord and can
                // re-enter a hook of this instance, which builds a newer record; a revision read afterwards would
                // already be that newer build's, and this older record would pass the check and overwrite it.
                long revision = beginBuild();
                scheduler.scheduleIfNotSuperseded(
                        this, buildRecord("RUNNING", info.operations(), null, cachedInput, null, null), revision);
            }
        } catch (Throwable t) {
            WorkflowInsight.logSafely("onInvocationStart failed", t);
        }
    }

    @Override
    public void onOperationChange(OperationChangeInfo info) {
        try {
            if (settings.emitMode != WorkflowInsightConfig.EmitMode.ON_CHANGE || !sampledIn) {
                return;
            }
            // Lock-free pre-check: this invocation's end may already have begun, in which case no RUNNING snapshot may
            // follow the final record. The authoritative check is made again under the scheduler's lock below.
            if (closed) {
                return;
            }
            long revision = beginBuild();
            scheduler.scheduleIfNotSuperseded(
                    this, buildRecord("RUNNING", info.operations(), null, cachedInput, null, null), revision);
        } catch (Throwable t) {
            WorkflowInsight.logSafely("onOperationChange failed", t);
        }
    }

    // onInvocationEnd is the hook the SDK awaits, so it is where the export queue is drained before the invocation
    // returns; this guarantees the final record (scheduled above the drain) is delivered. The drain and flush run
    // in finally so they also cover the paths where record construction fails.
    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        try {
            String status = WorkflowInsight.mapStatus(info.invocationStatus());
            boolean isTerminal = "SUCCEEDED".equals(status) || "FAILED".equals(status);
            boolean isFailure = "FAILED".equals(status);
            boolean shouldEmit;
            switch (settings.emitMode) {
                case ON_CHANGE:
                    shouldEmit = true;
                    break;
                case ON_FAILURE:
                    shouldEmit = isFailure;
                    break;
                case ON_COMPLETE:
                default:
                    shouldEmit = isTerminal;
                    break;
            }

            WorkflowInsightRecord finalRecord = null;
            if (sampledIn && shouldEmit) {
                // No build revision is taken here. Customer code running inside this build can start a newer RUNNING
                // build, which would make a revision taken here stale, and a checked hand-off would then drop the final
                // record and leave a RUNNING snapshot as this execution's last exported state. The final record is
                // instead ordered by `closed`, which closeAndSchedule sets in the same critical section that queues it.
                finalRecord = buildRecord(
                        status,
                        info.operations(),
                        Instant.now(),
                        cachedInput,
                        info.executionResult(),
                        info.executionError());
            }
            // Close before the drain below: an operation-change hook arriving from a checkpoint that completes
            // during the drain is rejected, so no RUNNING snapshot can follow (or replace) the final record.
            scheduler.closeAndSchedule(this, finalRecord);
        } catch (Throwable t) {
            // A plugin failure at end-of-invocation (record construction, transforms, truncation, export/flush,
            // or optional exporter class linkage) must never disrupt durable execution.
            WorkflowInsight.logSafely("onInvocationEnd failed", t);
        } finally {
            // If record construction failed above, this instance is still open: close it so a late change hook cannot
            // schedule into the drain. Idempotent when already closed.
            scheduler.closeAndSchedule(this, null);
            // Sampled-out invocations never schedule a record, so there is nothing to drain or flush. The drain needs
            // no lookup: this instance is the thing whose record it waits for.
            if (sampledIn) {
                drainAndFlush();
            }
            // Nothing is released here. There is no per-execution entry to remove — this instance is the state, the SDK
            // drops it when the invocation returns, and a suspended execution that resumes in the same container is
            // served by a new instance built from the resume's own InvocationInfo (same stable start time, same
            // deterministic sampling decision, its own input snapshot). A plugin failure therefore cannot turn into a
            // state leak, because there is no place a leak could accumulate.
        }
    }

    /**
     * Waits for this invocation's scheduled record to reach the exporters, then flushes each exporter once. The wait is
     * per invocation: another execution running in the same environment can never displace this record, so this always
     * returns having delivered this invocation's latest snapshot. It is not insulated from the queue, though — one pump
     * exports serially, so records another execution had already queued ahead of this one are exported first and this
     * drain waits for them too.
     *
     * <p>The flush goes through the scheduler's queue and is served by that same pump, between records, so no exporter
     * ever sees this invocation's {@code flush()} overlap another's {@code export()}. Invocation ends that overlap
     * share one flush: the cadence the exporter contract promises is at most one flush per sampled-in invocation end,
     * not exactly one.
     */
    private void drainAndFlush() {
        try {
            scheduler.drain(this);
        } catch (Throwable t) {
            WorkflowInsight.logSafely("failed to drain export scheduler", t);
        }
        try {
            scheduler.flush();
        } catch (Throwable t) {
            WorkflowInsight.logSafely("exporter flush failed", t);
        }
    }

    // --- Record building. ---

    /** Starts a record build and returns the revision that identifies it. */
    private long beginBuild() {
        return buildRevision.incrementAndGet();
    }

    /**
     * Whether the identified build is still the newest one this invocation has started.
     *
     * <p>Read by the scheduler inside the critical section that queues the record, so a record that passes cannot be
     * queued after a record that supersedes it. A build that starts after the check passes still supersedes this one:
     * its record is handed over later and replaces this one in the slot, which is the order the slot should have.
     */
    boolean isNewestBuild(long revision) {
        return buildRevision.get() == revision;
    }

    private WorkflowInsightRecord buildRecord(
            String status,
            Map<String, OperationChangeItemInfo> operations,
            Instant endTime,
            Object input,
            Object output,
            Throwable error) {
        ContentConfig content = settings.content;
        WorkflowInsightRecord record = new WorkflowInsightRecord();
        record.emittedAt = Instant.now().toString();
        record.executionArn = executionArn;
        record.executionName = WorkflowInsight.emptyToNull(arn.executionName());
        record.functionName = arn.functionName();
        record.functionQualifier = arn.qualifier();
        record.region = arn.region();
        record.accountId = arn.accountId();
        record.status = status;
        record.startTime = startTime != null ? startTime.toString() : null;
        if (endTime != null) {
            record.endTime = endTime.toString();
            if (startTime != null) {
                record.durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
            }
        }
        record.input = WorkflowInsight.applyDataContent(
                "input",
                input,
                content == null || content.includeInput(),
                content == null ? null : content.inputTransform());
        record.output = WorkflowInsight.applyDataContent(
                "output",
                output,
                content == null || content.includeOutput(),
                content == null ? null : content.outputTransform());
        // Honor ContentConfig.includeErrors for the execution-level error exactly as for operation-level errors
        // below: with includeErrors(false) no execution error is emitted, so a sensitive failure message never
        // reaches a record. Without this gate the execution error leaked even when errors were disabled.
        if (settings.includeErrors && error != null) {
            record.error = WorkflowInsight.toErrorInfo(error);
        }
        record.operations = buildOperationRecords(operations);
        return record;
    }

    private List<OperationRecord> buildOperationRecords(Map<String, OperationChangeItemInfo> operations) {
        List<OperationRecord> out = new ArrayList<>();
        if (operations == null) {
            return out;
        }
        // The hook contract supplies a map with no iteration-order guarantee (the core snapshot originates from a
        // concurrent map). Sort by startTimestamp ascending (null timestamps last), then by a stable operation id
        // tie-breaker, so the emitted operations array is deterministic and OperationsIndex's "latest occurrence"
        // scalar fields reflect true chronological order rather than arbitrary map iteration order.
        List<OperationChangeItemInfo> items = new ArrayList<>(operations.values());
        items.sort(Comparator.comparing(
                        OperationChangeItemInfo::startTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OperationChangeItemInfo::id, Comparator.nullsLast(Comparator.naturalOrder())));
        for (OperationChangeItemInfo item : items) {
            // The SDK core tracks the invocation/execution itself as a pseudo-entry of type EXECUTION; it is not a
            // customer operation and the record already carries the execution status/timing at top level.
            if ("EXECUTION".equals(item.type())) {
                continue;
            }
            // Unnamed operations can't be targeted or keyed — excluded by default (matches JS `if (!op.name)`).
            if (item.name() == null) {
                continue;
            }
            // top-level detail drops anything nested under a context (parallel branches, map items, nested steps).
            if (settings.topLevelOnly && item.parentId() != null) {
                continue;
            }
            OperationOverride override = settings.overridesByName.get(item.name());
            if (override != null && override.isExclude()) {
                continue;
            }
            OperationRecord rec = new OperationRecord()
                    .id(item.id())
                    .name(item.name())
                    .type(item.type())
                    .subType(item.subType())
                    .parentId(item.parentId())
                    .status(item.status() != null ? item.status().toString() : "UNKNOWN")
                    .startTime(
                            item.startTimestamp() != null
                                    ? item.startTimestamp().toString()
                                    : null)
                    .endTime(item.endTimestamp() != null ? item.endTimestamp().toString() : null)
                    .attempt(item.attempt());
            if (item.startTimestamp() != null && item.endTimestamp() != null) {
                rec.durationMs(item.endTimestamp().toEpochMilli()
                        - item.startTimestamp().toEpochMilli());
            }
            if (settings.includeErrors && item.error() != null) {
                rec.error(WorkflowInsight.toErrorInfo(item.error()));
            }
            // Results are omitted unless an override explicitly opts in via a transform (matches JS).
            if (override != null && override.result() != null) {
                rec.result(WorkflowInsight.applyResultOverride(override.result(), item.result()));
            }
            out.add(rec);
        }
        return out;
    }

    @Override
    public String toString() {
        return "InsightPlugin[" + executionArn + "]";
    }
}
