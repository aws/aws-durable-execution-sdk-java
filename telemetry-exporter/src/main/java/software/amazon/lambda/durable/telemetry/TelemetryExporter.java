// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

/** Exports workflow telemetry records to a destination. */
public interface TelemetryExporter {

    /** Emits one record to the destination. */
    void export(DurableExecutionTelemetryRecord record);

    /** Flushes any buffered records; no-op by default. */
    default void flush() {}

    /**
     * Maximum serialized record size, in bytes, this exporter will emit; {@code null} disables truncation. Measured
     * against {@link #render(DurableExecutionTelemetryRecord)}.
     */
    default Integer maxRecordSizeBytes() {
        return null;
    }

    /**
     * Maps a record to the exact value this exporter serializes/sends (defaults to the canonical {@code operations}
     * array wire map). Overriding exporters (CloudWatch/Lambda log) return the {@code operationsByName} rendering.
     */
    default Object render(DurableExecutionTelemetryRecord record) {
        return record.toWireMap();
    }
}
