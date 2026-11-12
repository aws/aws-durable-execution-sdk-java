// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry.exporters;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryRecord;
import software.amazon.lambda.durable.telemetry.TelemetryExporter;

/**
 * Test exporter in this package so the export path treats it as a built-in. Counts {@link #render} calls, which is how
 * truncation measures a record.
 */
public final class RenderCountingExporter implements TelemetryExporter {
    public final AtomicInteger renders = new AtomicInteger();
    public DurableExecutionTelemetryRecord exported;

    private final Function<DurableExecutionTelemetryRecord, Object> renderFn;
    private final int maxBytes;

    public RenderCountingExporter(OperationsFormat format, int maxBytes) {
        this(format::apply, maxBytes);
    }

    public RenderCountingExporter(Function<DurableExecutionTelemetryRecord, Object> renderFn, int maxBytes) {
        this.renderFn = renderFn;
        this.maxBytes = maxBytes;
    }

    @Override
    public Integer maxRecordSizeBytes() {
        return maxBytes;
    }

    @Override
    public Object render(DurableExecutionTelemetryRecord record) {
        renders.incrementAndGet();
        return renderFn.apply(record);
    }

    @Override
    public void export(DurableExecutionTelemetryRecord record) {
        exported = record;
    }
}
