// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_ARN;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_SYNTHETIC_ROOT;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates the SDK-owned zero-duration identity anchor for a fallback execution trace. */
final class DurableExecutionRoot {
    private static final Logger logger = LoggerFactory.getLogger(DurableExecutionRoot.class);

    private DurableExecutionRoot() {}

    /** Ends the anchor before customer code without letting an isolated processor failure suppress its descendants. */
    static void end(Span root, Instant timestamp) {
        if (root == null) return;
        try {
            root.end(timestamp);
        } catch (Exception | LinkageError failure) {
            logger.warn(
                    "Failed to end the execution root; continuing invocation telemetry and flushing at End", failure);
        }
    }

    /**
     * Starts the SDK-owned fallback ancestor before descendants so they inherit the provider's actual sampling
     * metadata. The caller ends this identity anchor immediately at the checkpointed start timestamp. Recovery
     * re-exports retain identity and timestamps; Workflow carries duration and outcome. Remote parents are never owned.
     */
    static Span start(
            Tracer tracer,
            DeterministicIdGenerator idGenerator,
            SpanContext ancestor,
            String arn,
            Instant start,
            DurableSamplingDecision.Intent intent,
            boolean useContextCarrier,
            boolean useThreadCarrier) {
        if (ancestor == null || ancestor.isRemote()) {
            return null;
        }
        var builder = tracer.spanBuilder("DurableExecutionRoot")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(useContextCarrier ? DurableSamplingDecision.store(Context.root(), intent) : Context.root())
                .setAttribute(DURABLE_EXECUTION_ARN, arn)
                .setAttribute(DURABLE_EXECUTION_SYNTHETIC_ROOT, true)
                .setStartTimestamp(start);
        Span root;
        if (useThreadCarrier) {
            try (var ignored = DurableSamplingDecision.openScope(intent)) {
                root = idGenerator.startSpan(builder, ancestor.getTraceId(), ancestor.getSpanId());
            }
        } else {
            root = idGenerator.startSpan(builder, ancestor.getTraceId(), ancestor.getSpanId());
        }
        return root;
    }
}
