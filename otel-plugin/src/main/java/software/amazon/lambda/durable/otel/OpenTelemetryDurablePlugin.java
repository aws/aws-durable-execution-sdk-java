// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/**
 * OpenTelemetry plugin for the AWS Lambda Durable Execution SDK.
 *
 * <p>Creates spans at three levels:
 *
 * <ul>
 *   <li><b>Invocation span</b> — one per Lambda invocation
 *   <li><b>Operation span</b> — created when an operation starts, ended when it completes or when the invocation ends
 *   <li><b>Attempt span</b> — one per user function execution (step attempt, child context run)
 * </ul>
 *
 * <p>Uses deterministic span/trace IDs so all invocations of the same execution share a single trace.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for span/scope storage since the SDK runs user code on multiple
 * threads.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public class OpenTelemetryDurablePlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(OpenTelemetryDurablePlugin.class);
    private static final String INSTRUMENTATION_NAME = "aws-durable-execution-sdk-java";

    private final TracerProvider tracerProvider;
    private final Tracer tracer;
    private final DeterministicIdGenerator idGenerator;
    private final ContextExtractor contextExtractor;
    private final double samplingRate;
    private final boolean enableMdc;

    // Per-invocation state
    private volatile Span invocationSpan;
    private volatile String executionArn;
    private volatile boolean sampled = true;

    // Thread-safe storage for operation spans (keyed by operationId) — open spans that need ending
    private final ConcurrentHashMap<String, Span> operationSpans = new ConcurrentHashMap<>();

    // Thread-safe storage for attempt spans/scopes (keyed by operationId + "-" + attempt)
    private final ConcurrentHashMap<String, Span> attemptSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scope> attemptScopes = new ConcurrentHashMap<>();

    // Store operation span contexts for parent resolution (keyed by operationId)
    private final ConcurrentHashMap<String, SpanContext> operationContexts = new ConcurrentHashMap<>();

    /**
     * Creates an OTel plugin with default settings: X-Ray context extraction, 100% sampling, MDC enabled.
     *
     * @param tracerProvider the OTel tracer provider (should use {@link DeterministicIdGenerator})
     * @param idGenerator the deterministic ID generator (same instance configured in the tracer provider)
     */
    public OpenTelemetryDurablePlugin(TracerProvider tracerProvider, DeterministicIdGenerator idGenerator) {
        this(tracerProvider, idGenerator, new XRayContextExtractor(), 1.0, true);
    }

    /**
     * Creates an OTel plugin with a custom context extractor and sampling rate, MDC enabled.
     *
     * @param tracerProvider the OTel tracer provider
     * @param idGenerator the deterministic ID generator
     * @param contextExtractor extracts parent trace context from the Lambda environment
     * @param samplingRate value between 0.0 and 1.0 — fraction of executions to trace
     */
    public OpenTelemetryDurablePlugin(
            TracerProvider tracerProvider,
            DeterministicIdGenerator idGenerator,
            ContextExtractor contextExtractor,
            double samplingRate) {
        this(tracerProvider, idGenerator, contextExtractor, samplingRate, true);
    }

    /**
     * Creates an OTel plugin with full configuration.
     *
     * @param tracerProvider the OTel tracer provider
     * @param idGenerator the deterministic ID generator
     * @param contextExtractor extracts parent trace context from the Lambda environment
     * @param samplingRate value between 0.0 and 1.0 — fraction of executions to trace
     * @param enableMdc if true, injects trace_id/span_id into SLF4J MDC for log correlation
     */
    public OpenTelemetryDurablePlugin(
            TracerProvider tracerProvider,
            DeterministicIdGenerator idGenerator,
            ContextExtractor contextExtractor,
            double samplingRate,
            boolean enableMdc) {
        this.tracerProvider = tracerProvider;
        this.idGenerator = idGenerator;
        this.tracer = tracerProvider.get(INSTRUMENTATION_NAME);
        this.contextExtractor = contextExtractor;
        this.samplingRate = samplingRate;
        this.enableMdc = enableMdc;
    }

    /**
     * Creates an OTel plugin using a pre-configured {@link SdkTracerProvider}.
     *
     * @param sdkTracerProvider the SDK tracer provider
     * @param idGenerator the deterministic ID generator
     */
    public OpenTelemetryDurablePlugin(SdkTracerProvider sdkTracerProvider, DeterministicIdGenerator idGenerator) {
        this((TracerProvider) sdkTracerProvider, idGenerator);
    }

    // ─── Invocation hooks ────────────────────────────────────────────────

    @Override
    public void onInvocationStart(InvocationInfo info) {
        this.executionArn = info.executionArn();
        idGenerator.setExecutionArn(info.executionArn());

        // Determine sampling (consistent across all invocations of same execution)
        this.sampled = SamplingUtil.shouldSampleExecution(info.executionArn(), samplingRate);
        if (!sampled) return;

        // Extract parent context from Lambda environment (X-Ray, W3C, etc.)
        var extractedParentContext = contextExtractor.extract();

        // Create invocation span as child of extracted context
        var spanBuilder = tracer.spanBuilder("durable.invocation")
                .setParent(extractedParentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, info.executionArn())
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        invocationSpan = spanBuilder.startSpan();
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        if (!sampled || invocationSpan == null) return;

        // End any operation spans that are still open (operations that didn't complete in this invocation)
        for (var entry : operationSpans.entrySet()) {
            var span = entry.getValue();
            span.setAttribute(AttributeKey.stringKey("durable.operation.status"), "PENDING");
            span.end();
        }
        operationSpans.clear();
        operationContexts.clear();

        // End any attempt spans that are still open (e.g., SuspendExecutionException skipped onUserFunctionEnd)
        for (var entry : attemptScopes.entrySet()) {
            entry.getValue().close();
        }
        attemptScopes.clear();
        for (var entry : attemptSpans.entrySet()) {
            entry.getValue().end();
        }
        attemptSpans.clear();

        // End invocation span
        invocationSpan.setAttribute(
                DURABLE_INVOCATION_STATUS, info.invocationStatus().name());

        if (info.invocationStatus() == InvocationStatus.FAILED && info.executionError() != null) {
            invocationSpan.setStatus(StatusCode.ERROR, info.executionError().getMessage());
            invocationSpan.recordException(info.executionError());
        }

        invocationSpan.end();
        invocationSpan = null;

        // Flush spans before Lambda freezes
        if (tracerProvider instanceof SdkTracerProvider sdkProvider) {
            var flushResult = sdkProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!flushResult.isSuccess()) {
                logger.warn("OTel span flush failed or timed out — some spans may be lost");
            }
        }
    }

    // ─── Operation hooks ─────────────────────────────────────────────────

    @Override
    public void onOperationStart(OperationInfo info) {
        if (!sampled || info.id() == null) return;

        idGenerator.setNextSpanOperationId(info.id());

        var parentContext = resolveParentContext(info.parentId());

        var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, executionArn)
                .setAttribute(DURABLE_OPERATION_ID, info.id())
                .setAttribute(DURABLE_OPERATION_TYPE, info.type());

        if (info.name() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
        }
        if (info.subType() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
        }
        if (info.parentId() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_PARENT_ID, info.parentId());
        }

        var span = spanBuilder.startSpan();

        // Store the open span — will be ended in onOperationEnd or onInvocationEnd
        operationSpans.put(info.id(), span);
        operationContexts.put(info.id(), span.getSpanContext());
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        if (!sampled || info.id() == null) return;

        // End the operation span that was started in onOperationStart
        var span = operationSpans.remove(info.id());
        if (span == null) return;

        if (info.error() != null) {
            span.setStatus(StatusCode.ERROR, info.error().getMessage());
            span.recordException(info.error());
        }

        span.end();
    }

    // ─── User function hooks ─────────────────────────────────────────────

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        if (!sampled) return;
        var key = attemptKey(info.id(), info.attempt());

        // Use the operation span as parent for the attempt span
        var parentContext = resolveParentContext(info.id());

        var spanBuilder = tracer.spanBuilder(attemptSpanName(info.type(), info.subType(), info.name(), info.attempt()))
                .setParent(parentContext)
                .setStartTimestamp(info.startTimestamp() != null ? info.startTimestamp() : Instant.now());

        spanBuilder.setAttribute(DURABLE_EXECUTION_ARN, executionArn);
        spanBuilder.setAttribute(DURABLE_OPERATION_ID, info.id());

        if (info.type() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_TYPE, info.type());
        }
        if (info.name() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
        }
        if (info.attempt() != null) {
            spanBuilder.setAttribute(DURABLE_ATTEMPT_NUMBER, info.attempt().longValue());
        }

        var span = spanBuilder.startSpan();
        attemptSpans.put(key, span);

        // Make span current on this thread so auto-instrumented calls become children
        var scope = span.makeCurrent();
        attemptScopes.put(key, scope);

        // Inject trace context into MDC for log-trace correlation
        if (enableMdc) {
            MdcSpanEnricher.inject(executionArn);
        }
    }

    @Override
    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        if (!sampled) return;
        var key = attemptKey(info.id(), info.attempt());

        // Close scope first (must happen on same thread as makeCurrent)
        var scope = attemptScopes.remove(key);
        if (scope != null) {
            scope.close();
        }

        var span = attemptSpans.remove(key);
        if (span == null) return;

        // Set outcome
        var outcome = info.succeeded() ? "succeeded" : (info.error() != null ? "failed" : "unknown");
        span.setAttribute(DURABLE_ATTEMPT_OUTCOME, outcome);

        if (!info.succeeded() && info.error() != null) {
            span.setStatus(StatusCode.ERROR, info.error().getMessage());
            span.recordException(info.error());
        }

        if (info.endTimestamp() != null) {
            span.end(info.endTimestamp());
        } else {
            span.end();
        }

        // Clear MDC after user function completes
        if (enableMdc) {
            MdcSpanEnricher.clear();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private Context resolveParentContext(String parentId) {
        if (parentId != null) {
            var parentSpanContext = operationContexts.get(parentId);
            if (parentSpanContext != null) {
                return Context.current().with(Span.wrap(parentSpanContext));
            }
            // Parent operation from a prior invocation — create non-recording placeholder
            var deterministicParentSpanId = idGenerator.generateSpanIdForOperation(parentId);
            var traceId = idGenerator.generateTraceId();
            var placeholderContext = SpanContext.create(
                    traceId, deterministicParentSpanId, TraceFlags.getSampled(), TraceState.getDefault());
            return Context.current().with(Span.wrap(placeholderContext));
        }
        // Fall back to invocation span as parent
        if (invocationSpan != null) {
            return Context.current().with(invocationSpan);
        }
        return Context.current();
    }

    private static String spanName(String type, String subType, String name) {
        if (name != null) {
            return "durable." + (subType != null ? subType.toLowerCase() : type.toLowerCase()) + ":" + name;
        }
        return "durable." + (subType != null ? subType.toLowerCase() : type.toLowerCase());
    }

    private static String attemptSpanName(String type, String subType, String name, Integer attempt) {
        var base = spanName(type, subType, name);
        if (attempt != null) {
            return base + " [attempt " + attempt + "]";
        }
        return base + " [fn]";
    }

    private static String attemptKey(String operationId, Integer attempt) {
        return operationId + "-" + (attempt != null ? attempt : "ctx");
    }
}
