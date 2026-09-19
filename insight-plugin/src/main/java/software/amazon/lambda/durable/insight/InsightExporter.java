// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import software.amazon.lambda.durable.annotations.Experimental;

/** Exports workflow insight records to a destination. */
@Experimental
public interface InsightExporter {

    /** Emits one record to the destination. */
    void export(WorkflowInsightRecord record);

    /**
     * Flushes any records this exporter has buffered. The default is a no-op; override it only if
     * {@link #export(WorkflowInsightRecord)} buffers rather than emitting immediately.
     *
     * <p>Called at most once per sampled-in invocation end, after that invocation's own record — if it emitted one —
     * has been handed to every exporter. An end that emits no record still flushes (a non-terminal suspend under
     * {@code ON_COMPLETE}, a success under {@code ON_FAILURE}), so records buffered by that execution's earlier
     * emissions are never left behind. Invocation ends that overlap may share a single flush: one flush is enough for
     * all of them, because it starts only after each of their records has been handed to every exporter. An execution
     * that is sampled out neither exports nor flushes.
     *
     * <p>Never called concurrently with {@link #export(WorkflowInsightRecord)} by the plugins one
     * {@link WorkflowInsight#workflowInsight} factory creates. That factory owns the scheduler serializing them, so the
     * guarantee is per factory rather than per environment: an exporter instance handed to two factories is served by
     * two schedulers, which can call its {@code export} and {@code flush} at the same time. Build the factory once per
     * handler — which is what a {@code DurableConfig} created once per handler does — and give each factory its own
     * exporter instances if a single exporter cannot tolerate concurrent calls.
     *
     * <p>May cover records belonging to other executions running in the same environment, so it is not a per-execution
     * barrier.
     *
     * <p>Must return promptly. No invocation whose end is waiting on this flush can return until it returns, and since
     * overlapping ends may share one flush, a slow flush is billed to every one of those invocations — not only to the
     * one that asked for it.
     *
     * <p>Failures are isolated: a {@link Throwable} thrown here is reported through the plugin's failure handler, never
     * retried, never propagated into the execution, and never prevents another exporter from flushing.
     */
    default void flush() {}

    /**
     * Maximum serialized record size, in bytes, this exporter will emit; {@code null} disables truncation. Measured
     * against {@link #render(WorkflowInsightRecord)}.
     */
    default Integer maxRecordSizeBytes() {
        return null;
    }

    /**
     * Maps a record to the exact value this exporter serializes/sends (defaults to the canonical {@code operations}
     * array wire map). Overriding exporters (CloudWatch/Lambda log) return the {@code operationsByName} rendering.
     */
    default Object render(WorkflowInsightRecord record) {
        return record.toWireMap();
    }
}
