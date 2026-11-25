// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Tracks real export completions so forceFlush can exercise an outstanding exporter. */
final class OtelLedgerProcessor implements SpanProcessor, SpanExporter {
    private final Set<CompletableResultCode> pending = ConcurrentHashMap.newKeySet();
    private final Consumer<InvocationTrace> hold;
    final AtomicInteger shutdowns = new AtomicInteger();
    private final ExecutorService exporter = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "lmi-otel-export");
        thread.setDaemon(true);
        return thread;
    });

    OtelLedgerProcessor(Consumer<InvocationTrace> hold) {
        this.hold = hold;
    }

    @Override
    public void onStart(Context parent, ReadWriteSpan span) {}

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan ended) {
        export(List.of(ended.toSpanData()));
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        var trace = ObservedOtelPlugin.EXPORT_OWNER.get();
        if (trace == null) throw new IllegalStateException("Export lost independent invocation ownership");
        var results = new ArrayList<CompletableResultCode>();
        for (var data : spans) results.add(enqueue(trace, data));
        return CompletableResultCode.ofAll(results);
    }

    private CompletableResultCode enqueue(InvocationTrace trace, SpanData data) {
        var result = new CompletableResultCode();
        pending.add(result);
        result.whenComplete(() -> pending.remove(result));
        trace.exportEnter();
        if ("held".equals(trace.input.otelExport())) {
            trace.event("OTEL_EXPORT_HELD");
            exporter.execute(() -> export(trace, data, result));
        } else {
            export(trace, data, result);
        }
        return result;
    }

    private void export(InvocationTrace trace, SpanData data, CompletableResultCode result) {
        var success = false;
        try {
            if ("held".equals(trace.input.otelExport())) hold.accept(trace);
            var failed = "failure".equals(trace.input.otelExport());
            trace.event(
                    "OTEL_EXPORT", Map.of("span", OtelRuntime.span(data), "outcome", failed ? "failure" : "success"));
            success = !failed;
        } catch (RuntimeException failure) {
            trace.event("OTEL_EXPORT_ERROR", Map.of("error", failure.getClass().getSimpleName()));
        } finally {
            trace.exportExit();
            if (success) result.succeed();
            else result.fail();
        }
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofAll(new ArrayList<>(pending));
    }

    @Override
    public CompletableResultCode flush() {
        return forceFlush();
    }

    @Override
    public CompletableResultCode shutdown() {
        shutdowns.incrementAndGet();
        exporter.shutdown();
        return forceFlush();
    }

    @Override
    public void close() {
        shutdown().join(5, TimeUnit.SECONDS);
    }
}
