// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

/**
 * Injects OTel trace/span IDs into SLF4J MDC for log-trace correlation.
 *
 * <p>When used with structured logging (Log4j2 JSON, Logback JSON), these MDC fields appear in every log line, enabling
 * tools like CloudWatch Logs Insights and Datadog to correlate logs with traces.
 *
 * <p>MDC keys injected:
 *
 * <ul>
 *   <li>{@code trace_id} — the W3C trace ID (32 hex chars)
 *   <li>{@code span_id} — the current span ID (16 hex chars)
 *   <li>{@code durable.execution.arn} — the execution ARN
 * </ul>
 *
 * <p>Usage: Call {@link #inject()} in {@code onUserFunctionStart} (after span is active) and {@link #clear()} in
 * {@code onUserFunctionEnd}. Or use the convenience plugin {@link OpenTelemetryDurablePlugin} which handles this
 * automatically when MDC enrichment is enabled.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class MdcSpanEnricher {

    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";
    public static final String MDC_EXECUTION_ARN = "durable.execution.arn";

    private MdcSpanEnricher() {}

    /**
     * Injects the current span's trace ID and span ID into MDC.
     *
     * @param durableExecutionArn the durable execution ARN (may be null)
     */
    public static void inject(String durableExecutionArn) {
        var span = Span.current();
        if (span.getSpanContext().isValid()) {
            MDC.put(MDC_TRACE_ID, span.getSpanContext().getTraceId());
            MDC.put(MDC_SPAN_ID, span.getSpanContext().getSpanId());
        }
        if (durableExecutionArn != null) {
            MDC.put(MDC_EXECUTION_ARN, durableExecutionArn);
        }
    }

    /** Removes the injected MDC fields. */
    public static void clear() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_EXECUTION_ARN);
    }
}
