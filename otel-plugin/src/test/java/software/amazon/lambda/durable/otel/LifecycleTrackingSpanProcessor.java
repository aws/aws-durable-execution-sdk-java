// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test {@link SpanProcessor} that records span lifecycle events — both {@code onStart} and {@code onEnd} — keyed by
 * span ID.
 *
 * <p>{@code InMemorySpanExporter} only receives spans that have been ended, so asserting "span X was not exported" is
 * satisfied both when X never started and when X started but was abandoned un-ended. This processor closes that gap: it
 * observes every recording span at start, so a test can assert that every started span also ended (nothing is left
 * open) or that a deferred span never started at all.
 */
final class LifecycleTrackingSpanProcessor implements SpanProcessor {

    private final List<String> startedNames = new CopyOnWriteArrayList<>();
    private final Map<String, String> openSpanNamesById = new ConcurrentHashMap<>();

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        startedNames.add(span.getName());
        openSpanNamesById.put(span.getSpanContext().getSpanId(), span.getName());
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        openSpanNamesById.remove(span.getSpanContext().getSpanId());
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    /** Names of all spans that were started (recording), whether or not they were later ended. */
    List<String> startedSpanNames() {
        return List.copyOf(startedNames);
    }

    /** Names of spans that started but were never ended — i.e. abandoned recording spans. */
    List<String> openSpanNames() {
        return List.copyOf(openSpanNamesById.values());
    }
}
