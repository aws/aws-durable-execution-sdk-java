// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package io.opentelemetry.exporter.otlp.trace;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class OtlpGrpcSpanExporter implements SpanExporter {

    private static final List<SpanData> FINISHED_SPANS = new CopyOnWriteArrayList<>();

    public static SpanExporter getDefault() {
        return new OtlpGrpcSpanExporter();
    }

    public static List<SpanData> getFinishedSpanItems() {
        return new ArrayList<>(FINISHED_SPANS);
    }

    public static void reset() {
        FINISHED_SPANS.clear();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        FINISHED_SPANS.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
