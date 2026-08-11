// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

/**
 * Injects OTel trace/span IDs into SLF4J MDC for log-trace correlation.
 *
 * <p>When used with structured logging (Log4j2 JSON, Logback JSON), these MDC fields appear in every log line, enabling
 * tools like CloudWatch Application Signals and Datadog to correlate logs with traces.
 *
 * <p>MDC keys injected (aligned with the JS and Python SDK OTel plugins):
 *
 * <ul>
 *   <li>{@code traceId} — the W3C trace ID (32 hex chars)
 *   <li>{@code spanId} — the current span ID (16 hex chars)
 *   <li>{@code otelTraceSampled} — whether the trace is sampled (true/false)
 * </ul>
 *
 * <p>Usage: Call {@link #inject()} in {@code onUserFunctionStart} (after span is active) and {@link #clear()} in
 * {@code onUserFunctionEnd}. Or use the convenience plugin {@link InvocationOtelPlugin} which handles this
 * automatically when MDC enrichment is enabled.
 *
 */
public final class MdcSpanEnricher {

    // MDC key names are aligned with the JS and Python SDK OTel plugins
    // (enrichLogContext -> traceId/spanId/otelTraceSampled) so log-trace
    // correlation uses one consistent field schema across all three SDKs.
    // Note: SLF4J MDC values are always strings, so otelTraceSampled is the
    // string "true"/"false" here (a boolean in the JS/Python log records).
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_TRACE_SAMPLED = "otelTraceSampled";

    private MdcSpanEnricher() {}

    /** Injects the current span's trace ID, span ID, and sampling flag into MDC. */
    public static void inject() {
        var span = Span.current();
        if (span.getSpanContext().isValid()) {
            MDC.put(MDC_TRACE_ID, span.getSpanContext().getTraceId());
            MDC.put(MDC_SPAN_ID, span.getSpanContext().getSpanId());
            MDC.put(MDC_TRACE_SAMPLED, String.valueOf(span.getSpanContext().isSampled()));
        }
    }

    /** Removes the injected MDC fields. */
    public static void clear() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_TRACE_SAMPLED);
    }
}
