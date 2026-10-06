// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import software.amazon.lambda.durable.annotations.Experimental;

/** Exports workflow insight records to a destination. */
@Experimental
public interface InsightExporter {

    /** Emits one record to the destination. */
    void export(WorkflowInsightRecord record);

    /** Flushes any buffered records; no-op by default. */
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
