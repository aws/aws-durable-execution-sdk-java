// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/**
 * OpenTelemetry plugin for the AWS Lambda Durable Execution SDK.
 *
 * <p>Creates spans at these levels:
 *
 * <ul>
 *   <li><b>Workflow span</b> — one logical span per durable execution (deterministic ID from the ARN, started and ended
 *       together on the terminal invocation only, so it is never left open). Between invocations it exists only as a
 *       deterministic context that operation and attempt spans <em>link</em> to for execution-level correlation; they
 *       remain parented to the per-invocation span (this plugin is invocation-rooted).
 *   <li><b>Invocation span</b> — one per Lambda invocation
 *   <li><b>Operation span</b> — created when an operation starts, ended when it completes or when the invocation ends
 *   <li><b>Attempt span</b> — one per user function execution (step attempt, child context run)
 * </ul>
 *
 * <p>The Workflow span is parented onto the execution ancestor resolved at invocation start (the propagated remote
 * server span when one is valid, otherwise a synthetic execution root), so it joins the execution trace with a trace ID
 * stable across invocations. It serves as a correlation anchor: operation and attempt spans link to it while remaining
 * parented to the per-invocation span. The Invocation span parents onto the same-trace ambient span when available,
 * otherwise onto the execution ancestor so it stays on the execution trace.
 *
 * <p>Requires the ADOT Lambda Layer for trace export. Configure with:
 *
 * <ul>
 *   <li>Lambda Layer: {@code AWSOpenTelemetryDistroJava} (provides the ADOT Java agent and export pipeline)
 *   <li>Tracing: Active (to populate {@code _X_AMZN_TRACE_ID})
 * </ul>
 *
 * <p>When using {@link #InvocationOtelPlugin()}, the plugin resolves the global provider at invocation start. If the
 * OpenTelemetry Java agent is not initialized yet, telemetry is disabled for that entire invocation and provider
 * resolution is retried on the next invocation.
 *
 * <p><b>X-Ray console limitation:</b> In the X-Ray "Segments Timeline" ungrouped view, the plugin's spans (Invocation,
 * operation, attempt) do not appear as nested subsegments of the Lambda platform segment. This is a known limitation of
 * the OTLP-to-X-Ray conversion: the ADOT collector cannot attach OTLP-exported spans as subsegments of the Lambda
 * service's native X-Ray segment because that segment is created outside the OTLP pipeline. Use the "Group by nodes"
 * view to inspect parent-child relationships within the shared execution trace and the links between operation spans
 * and the Workflow span.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for span/scope storage since the SDK runs user code on multiple
 * threads.
 */
public class InvocationOtelPlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(InvocationOtelPlugin.class);

    private volatile SdkTracerProvider sdkTracerProvider;
    private volatile Tracer tracer;
    private final DeterministicIdGenerator idGenerator;
    private final ContextExtractor contextExtractor;
    private final boolean enableMdc;
    private final String workflowSpanName;
    private final String instrumentationName;

    // Per-invocation state
    private volatile boolean tracingEnabled;
    private volatile Span invocationSpan;
    private volatile String durableExecutionArn;
    // Trace ID and flags of the execution trace, published together as one snapshot so readers never pair a trace ID
    // with mismatched flags.
    private volatile ExecutionTrace executionTrace;
    // The execution's single sampling intent for this invocation, computed once at onInvocationStart and attached to
    // every durable span's parent context so DurableSampler applies it (a resolved decision verbatim, or a deferral to
    // its own delegate) without re-invoking the configured sampler per span.
    private volatile DurableSamplingDecision.Intent samplingIntent;

    // Deferred Workflow placeholder; the recording span is emitted only on terminal invocation.
    private volatile SpanContext workflowSpanContext;
    private volatile SpanContext executionAncestor;
    private volatile Instant executionStartTime;

    /** Immutable snapshot of the resolved execution trace, read atomically through a single volatile reference. */
    private record ExecutionTrace(String traceId, TraceFlags flags) {}

    // Thread-safe storage for operation spans (keyed by operationId) — open spans that need ending
    private final ConcurrentHashMap<String, Span> operationSpans = new ConcurrentHashMap<>();

    // Thread-safe storage for attempt spans/scopes (keyed by operationId + "-" + attempt)
    private final ConcurrentHashMap<String, Span> attemptSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scope> attemptScopes = new ConcurrentHashMap<>();

    // Store operation span contexts for parent resolution (keyed by operationId)
    private final ConcurrentHashMap<String, SpanContext> operationContexts = new ConcurrentHashMap<>();

    // Operation start order, drained in reverse so children end before parents
    private final ConcurrentLinkedDeque<String> operationStartOrder = new ConcurrentLinkedDeque<>();

    /**
     * Creates an OTel plugin with default settings: X-Ray context extraction, MDC enabled.
     *
     * <p>Uses the provided tracer provider builder. Customers configure exporters and span processors on the builder —
     * the plugin handles ID generation.
     *
     * <p>For ADOT Java agent usage, prefer {@link #InvocationOtelPlugin()} with the plugin jar configured through
     * {@code OTEL_JAVAAGENT_EXTENSIONS}. Use this builder constructor when you want to own the exporter pipeline:
     *
     * <pre>{@code
     * var exporter = LoggingSpanExporter.create();
     * var plugin = new InvocationOtelPlugin(
     *     SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)));
     * }</pre>
     *
     * @param tracerProviderBuilder the tracer provider builder (its ID generator will be wrapped)
     */
    public InvocationOtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder) {
        this(tracerProviderBuilder, OtelPluginConfig.defaults());
    }

    /**
     * Creates an OTel plugin with default settings: X-Ray context extraction and MDC enabled.
     *
     * <p>Resolves {@code GlobalOpenTelemetry} at invocation start. If the ADOT Java agent has not initialized it yet,
     * telemetry is disabled for that invocation and resolution is retried on the next invocation.
     */
    public InvocationOtelPlugin() {
        this(OtelPluginConfig.defaults());
    }

    /**
     * Creates an OTel plugin from the given tracer provider builder and configuration.
     *
     * <p>Customers configure exporters and span processors on the builder; all other tunables (context extractor, MDC
     * toggle, Workflow span name, instrumentation scope name) come from {@link OtelPluginConfig}. Use
     * {@link OtelPluginConfig#builder()} for readable, named configuration:
     *
     * <pre>{@code
     * var plugin = new InvocationOtelPlugin(
     *     SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
     *     OtelPluginConfig.builder().enableMdc(false).workflowSpanName("Workflow").build());
     * }</pre>
     *
     * @param tracerProviderBuilder the tracer provider builder (its ID generator will be wrapped)
     * @param config the plugin configuration
     */
    public InvocationOtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder, OtelPluginConfig config) {
        this.idGenerator = DeterministicIdGenerator.installOn(tracerProviderBuilder);
        // Wrap the configured sampler so durable spans use the execution's single precomputed decision.
        DurableSampler.installOn(tracerProviderBuilder);

        this.sdkTracerProvider = tracerProviderBuilder.build();
        this.tracer = sdkTracerProvider.get(config.instrumentationName());
        this.contextExtractor = config.contextExtractor();
        this.enableMdc = config.enableMdc();
        this.workflowSpanName = config.workflowSpanName();
        this.instrumentationName = config.instrumentationName();
    }

    /**
     * Creates an OTel plugin from configuration alone (no caller-supplied tracer provider builder).
     *
     * <p>The config-only constructor uses the ADOT/global provider. Supply a {@code SdkTracerProviderBuilder} via the
     * two-arg constructor for an application-owned provider.
     *
     * @param config the plugin configuration
     */
    public InvocationOtelPlugin(OtelPluginConfig config) {
        this.contextExtractor = config.contextExtractor();
        this.enableMdc = config.enableMdc();
        this.workflowSpanName = config.workflowSpanName();
        this.instrumentationName = config.instrumentationName();
        this.idGenerator = OtelPluginSupport.createDefaultIdGenerator();
    }

    // ─── Invocation hooks ────────────────────────────────────────────────

    @Override
    public void onInvocationStart(InvocationInfo info) {
        tracingEnabled = false;
        if (!bindTracer()) {
            return;
        }

        this.durableExecutionArn = info.durableExecutionArn();

        var extracted = contextExtractor.extract();

        // Resolve the execution ancestor the Workflow span parents onto so it joins the stable-per-execution trace.
        var canonicalTraceId = ExecutionTraceContext.canonicalTraceId(
                extracted, info.durableExecutionArn(), info.executionStartTime(), idGenerator);
        // Resolve the execution's sampling decision once for this invocation as a full SamplingResult, then apply it to
        // every durable span via DurableSampler (see below). The execution ancestor's trace flags are derived from the
        // same decision so a parent-based sampler stays consistent with it.
        var decision = OtelPluginSupport.resolveSamplingResult(
                sdkTracerProvider,
                extracted,
                Span.current(),
                canonicalTraceId,
                workflowSpanName,
                Attributes.of(DURABLE_EXECUTION_ARN, info.durableExecutionArn()));
        // A null decision is unresolved on the agent path: defer to DurableSampler's own delegate (keyed by trace ID),
        // rather than fabricating a decision that would bypass an installed drop/rate-limit policy.
        samplingIntent = decision != null
                ? DurableSamplingDecision.Intent.resolved(decision)
                : DurableSamplingDecision.Intent.deferred(canonicalTraceId);
        var sampled = OtelPluginSupport.isSampled(decision);
        var execCtx = ExecutionTraceContext.resolve(
                extracted, canonicalTraceId, info.durableExecutionArn(), idGenerator, () -> sampled);
        executionTrace = new ExecutionTrace(canonicalTraceId, execCtx.traceFlags());
        executionAncestor = execCtx.executionAncestor();
        executionStartTime = info.executionStartTime();

        // Invocation span parent — the same-trace ambient span when available, then the execution ancestor, so the
        // Invocation span stays on the execution trace.
        var parentContext = invocationParentContext(execCtx, canonicalTraceId);

        // Create an INTERNAL span for the invocation.
        var spanBuilder = tracer.spanBuilder("Invocation")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        invocationSpan = startDurableSpan(spanBuilder);

        // Defer the recording Workflow span until terminal completion. The placeholder uses the Invocation span's
        // resolved sampling metadata so operation links match the span that is eventually exported.
        var workflowSpanId = idGenerator.generateWorkflowSpanId(info.durableExecutionArn());
        var invocationContext = invocationSpan.getSpanContext();
        workflowSpanContext = SpanContext.create(
                canonicalTraceId, workflowSpanId, invocationContext.getTraceFlags(), invocationContext.getTraceState());

        // Inject MDC on the handler thread so handler-level logs (between steps) have trace context.
        // This runs on the same thread as context.getLogger() calls in the handler.
        if (enableMdc) {
            MDC.put(
                    MdcSpanEnricher.MDC_TRACE_ID,
                    invocationSpan.getSpanContext().getTraceId());
        }
        tracingEnabled = true;
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        if (!tracingEnabled) {
            return;
        }
        tracingEnabled = false;

        // Clear invocation-level MDC (set in onInvocationStart on the handler thread)
        if (enableMdc) {
            MdcSpanEnricher.clear();
        }

        if (invocationSpan == null) return;

        endOpenSpansChildFirst();

        // End invocation span
        invocationSpan.setAttribute(
                DURABLE_INVOCATION_STATUS, info.invocationStatus().name());

        // Invocation span status mapping:
        //   SUCCEEDED, PENDING -> OK
        //   FAILED             -> ERROR (records the execution error when present)
        //   RETRYING           -> UNSET
        // RETRYING is left UNSET on purpose: the plugin interface does not expose whether the invocation/workflow
        // was STOPPED or TIMED_OUT versus retried for a transient error, so an ERROR status cannot be asserted
        // reliably for a retrying invocation.
        switch (info.invocationStatus()) {
            case SUCCEEDED, PENDING -> invocationSpan.setStatus(StatusCode.OK);
            case FAILED -> {
                var message =
                        info.executionError() != null ? info.executionError().getMessage() : null;
                invocationSpan.setStatus(StatusCode.ERROR, message);
                if (info.executionError() != null) {
                    invocationSpan.recordException(info.executionError());
                }
            }
            case RETRYING -> {
                // UNSET — see note above.
            }
        }

        invocationSpan.end();
        invocationSpan = null;

        // Materialize the Workflow span only on terminal status.
        if (isTerminal(info) && workflowSpanContext != null && executionAncestor != null) {
            var workflowSpanBuilder = tracer.spanBuilder(workflowSpanName)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(withDurableDecision(Context.root().with(Span.wrap(executionAncestor))))
                    .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                    .setAttribute(
                            DURABLE_EXECUTION_STATUS, info.invocationStatus().name())
                    .setStartTimestamp(executionStartTime != null ? executionStartTime : Instant.now());
            // Force only the deterministic span ID; the parent supplies the execution trace ID.
            var workflowSpan = startDurableSpan(workflowSpanBuilder, null, workflowSpanContext.getSpanId());
            switch (info.invocationStatus()) {
                case FAILED -> {
                    var message = info.executionError() != null
                            ? info.executionError().getMessage()
                            : null;
                    workflowSpan.setStatus(StatusCode.ERROR, message);
                    if (info.executionError() != null) {
                        workflowSpan.recordException(info.executionError());
                    }
                }
                default -> workflowSpan.setStatus(StatusCode.OK); // SUCCEEDED
            }
            workflowSpan.end();
        }
        workflowSpanContext = null;
        executionAncestor = null;
        executionStartTime = null;
        samplingIntent = null;

        if (sdkTracerProvider != null) {
            // Flush spans before Lambda freezes
            var flushResult = sdkTracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
            if (!flushResult.isSuccess()) {
                logger.warn("OTel span flush failed or timed out — some spans may be lost");
            }
        }
    }

    // ─── Operation hooks ─────────────────────────────────────────────────

    @Override
    public void onOperationStart(OperationInfo info) {
        if (!tracingEnabled) return;
        if (info.id() == null) return;

        var parentContext = resolveParentContext(info.parentId());

        var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                .setAttribute(DURABLE_OPERATION_ID, info.id())
                .setAttribute(DURABLE_OPERATION_TYPE, info.type())
                .setAttribute(DURABLE_OPERATION_STATUS, info.status() != null ? info.status() : "STARTED");

        // On replay, this span is a distinct segment of an operation whose initial span ran in an earlier invocation;
        // link back to that initial logical operation span first, then to the Workflow span, so the links are ordered
        // [operation, Workflow]. A non-replay operation span carries only the Workflow link.
        if (info.isReplay()) {
            addInitialOperationLink(spanBuilder, info.id());
        }
        // Link to the Workflow span for execution-level correlation (operation stays parented to the invocation span).
        addWorkflowLink(spanBuilder);

        if (info.name() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
        }
        if (info.subType() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
        }

        var span = info.isReplay()
                ? startDurableSpan(spanBuilder)
                : startDurableSpan(
                        spanBuilder, null, idGenerator.generateSpanIdForOperation(durableExecutionArn, info.id()));

        // Store the open span — will be ended in onOperationEnd or onInvocationEnd
        operationSpans.put(info.id(), span);
        operationContexts.put(info.id(), span.getSpanContext());
        operationStartOrder.addLast(info.id());
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        if (!tracingEnabled) return;
        if (info.id() == null) return;

        var span = operationSpans.remove(info.id());

        if (span != null) {
            // Operation was started in this invocation — end normally
            if (info.status() != null) {
                span.setAttribute(DURABLE_OPERATION_STATUS, info.status());
            }
            // Total attempts for retriable operations (STEP, WAIT_FOR_CONDITION) — emitted only at end.
            if (info.attempt() != null) {
                span.setAttribute(DURABLE_ATTEMPT_NUMBER, info.attempt().longValue());
            }
            if (info.error() != null) {
                span.setStatus(StatusCode.ERROR, info.error().getMessage());
                span.recordException(info.error());
            } else if ("SUCCEEDED".equals(info.status()) || info.status() == null) {
                // Only stamp OK on genuine success. onOperationEnd fires for every terminal status, and
                // extractErrorFromOperation returns null for CANCELLED (always) and for FAILED/TIMED_OUT/STOPPED
                // with no attached error object — those carry a non-null, non-SUCCEEDED status and must stay UNSET.
                // A null status is a successful statusless virtual (FLAT CONTEXT) operation, which is OK.
                span.setStatus(StatusCode.OK);
            }
            span.end();
        } else {
            var parentContext = resolveParentContext(info.parentId());

            var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                    .setParent(parentContext)
                    .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                    .setAttribute(DURABLE_OPERATION_ID, info.id())
                    .setAttribute(DURABLE_OPERATION_TYPE, info.type());
            // This continuation segment completes an operation whose initial span ran earlier; link back to that
            // initial operation span first, then to the Workflow span (the spec expects [operation, Workflow]).
            addInitialOperationLink(spanBuilder, info.id());
            addWorkflowLink(spanBuilder);

            if (info.name() != null) {
                spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
            }
            if (info.subType() != null) {
                spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
            }

            var continuationSpan = startDurableSpan(spanBuilder);

            if (info.status() != null) {
                continuationSpan.setAttribute(DURABLE_OPERATION_STATUS, info.status());
            }
            if (info.attempt() != null) {
                continuationSpan.setAttribute(
                        DURABLE_ATTEMPT_NUMBER, info.attempt().longValue());
            }
            if (info.error() != null) {
                continuationSpan.setStatus(StatusCode.ERROR, info.error().getMessage());
                continuationSpan.recordException(info.error());
            } else if ("SUCCEEDED".equals(info.status()) || info.status() == null) {
                // See onOperationEnd (this-invocation branch): only genuine success (or a successful statusless
                // virtual operation) is OK; error-less non-success statuses stay UNSET.
                continuationSpan.setStatus(StatusCode.OK);
            }

            continuationSpan.end();
        }
    }

    // ─── User function hooks ─────────────────────────────────────────────

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        if (!tracingEnabled) return;

        // Skip attempt spans for CONTEXT operations — they are a scoping construct, not a
        // retriable unit of work, so attempt number/outcome attributes don't apply.
        // The operation span itself provides parent context for auto-instrumented calls.
        if ("CONTEXT".equals(info.type())) {
            // Still set the operation span as current so auto-instrumented calls become children
            var operationSpan = operationSpans.get(info.id());
            if (operationSpan != null) {
                var scope = operationSpan.makeCurrent();
                var key = attemptKey(info.id(), info.attempt());
                attemptScopes.put(key, scope);
            }
            if (enableMdc) {
                MdcSpanEnricher.inject();
            }
            return;
        }

        var key = attemptKey(info.id(), info.attempt());

        // Use the operation span as parent for the attempt span
        var parentContext = resolveParentContext(info.id());

        var spanBuilder = tracer.spanBuilder(attemptSpanName(info.type(), info.subType(), info.name(), info.attempt()))
                .setParent(parentContext)
                .setStartTimestamp(info.startTimestamp() != null ? info.startTimestamp() : Instant.now());
        addWorkflowLink(spanBuilder);

        spanBuilder.setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn);
        spanBuilder.setAttribute(DURABLE_OPERATION_ID, info.id());

        if (info.type() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_TYPE, info.type());
        }
        if (info.name() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
        }
        if (info.subType() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
        }
        if (info.attempt() != null) {
            spanBuilder.setAttribute(DURABLE_ATTEMPT_NUMBER, info.attempt().longValue());
        }

        var span = startDurableSpan(spanBuilder);
        attemptSpans.put(key, span);

        // Make span current on this thread so auto-instrumented calls become children
        var scope = span.makeCurrent();
        attemptScopes.put(key, scope);

        // Inject trace context into MDC for log-trace correlation
        if (enableMdc) {
            MdcSpanEnricher.inject();
        }
    }

    @Override
    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        if (!tracingEnabled) return;

        var key = attemptKey(info.id(), info.attempt());

        // Close scope first (must happen on same thread as makeCurrent)
        var scope = attemptScopes.remove(key);
        if (scope != null) {
            scope.close();
        }

        // Clear span-level MDC after user function completes (keep trace_id for handler-level logs between steps)
        if (enableMdc) {
            MDC.remove(MdcSpanEnricher.MDC_SPAN_ID);
        }

        // CONTEXT operations don't have attempt spans — scope cleanup is all we need
        if ("CONTEXT".equals(info.type())) {
            return;
        }

        var span = attemptSpans.remove(key);
        if (span == null) return;

        span.setAttribute(DURABLE_ATTEMPT_OUTCOME, info.outcome().name());

        switch (info.outcome()) {
            case SUCCEEDED -> span.setStatus(StatusCode.OK);
            case FAILED -> {
                if (info.error() != null) {
                    span.setStatus(StatusCode.ERROR, info.error().getMessage());
                    span.recordException(info.error());
                }
            }
            case INCOMPLETE -> {
                // An incomplete user function is expected when the durable execution suspends.
            }
        }

        if (info.endTimestamp() != null) {
            span.end(info.endTimestamp());
        } else {
            span.end();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private boolean bindTracer() {
        if (tracer != null) {
            return true;
        }
        synchronized (this) {
            if (tracer != null) {
                return true;
            }
            var setup = OtelPluginSupport.tryResolveGlobalProvider(instrumentationName, "InvocationOtelPlugin");
            if (setup == null) {
                return false;
            }
            sdkTracerProvider = setup.sdkTracerProvider();
            tracer = setup.tracer();
            return true;
        }
    }

    private void endOpenSpansChildFirst() {
        // Attempt spans are children of operation spans.
        for (var scope : attemptScopes.values()) {
            scope.close();
        }
        attemptScopes.clear();
        for (var span : attemptSpans.values()) {
            span.end();
        }
        attemptSpans.clear();

        // End still-open operation spans with the STARTED status set in onOperationStart.
        // A later invocation's onOperationEnd emits a continuation span with the real terminal status.
        String operationId;
        while ((operationId = operationStartOrder.pollLast()) != null) {
            var span = operationSpans.remove(operationId);
            if (span != null) {
                span.end();
            }
        }
        operationSpans.clear();
        operationContexts.clear();
    }

    /**
     * The parent context for the Invocation span: the active ambient span when it is on the execution trace, otherwise
     * the execution ancestor so the Invocation span stays within the same trace.
     */
    private Context invocationParentContext(ExecutionTraceContext execCtx, String canonicalTraceId) {
        var ambient = Span.current().getSpanContext();
        if (ambient.isValid() && ambient.getTraceId().equals(canonicalTraceId)) {
            return withDurableDecision(Context.root().with(Span.current()));
        }
        return withDurableDecision(Context.root().with(Span.wrap(execCtx.executionAncestor())));
    }

    private Context resolveParentContext(String parentId) {
        if (parentId != null) {
            var parentSpanContext = operationContexts.get(parentId);
            if (parentSpanContext != null) {
                return withDurableDecision(Context.current().with(Span.wrap(parentSpanContext)));
            }
        }
        // Fall back to invocation span as parent
        if (invocationSpan != null) {
            return withDurableDecision(Context.current().with(invocationSpan));
        }
        return withDurableDecision(Context.current());
    }

    /**
     * Attaches the execution's sampling intent to a durable span's parent context so {@link DurableSampler} applies it
     * (a resolved decision verbatim, or a deferral to its own delegate) instead of re-invoking the configured sampler
     * per span. When no intent has been resolved (telemetry disabled for the invocation) the context is unchanged.
     */
    private Context withDurableDecision(Context context) {
        var intent = samplingIntent;
        return intent != null ? DurableSamplingDecision.store(context, intent) : context;
    }

    /**
     * Starts a durable span with the execution's sampling intent published on the current thread for the duration of
     * the sampler call, so {@link DurableSampler} applies it even when the plugin and the agent-installed sampler run
     * in different class loaders (see {@link DurableSamplingDecision}). Falls back to a plain start when no intent has
     * been resolved.
     */
    private Span startDurableSpan(SpanBuilder spanBuilder) {
        var intent = samplingIntent;
        if (intent == null) {
            return spanBuilder.startSpan();
        }
        try (var ignored = DurableSamplingDecision.openScope(intent)) {
            return spanBuilder.startSpan();
        }
    }

    /** Starts a durable span with a forced span ID, publishing the sampling intent as in {@link #startDurableSpan}. */
    private Span startDurableSpan(SpanBuilder spanBuilder, String traceId, String spanId) {
        var intent = samplingIntent;
        if (intent == null) {
            return idGenerator.startSpan(spanBuilder, traceId, spanId);
        }
        try (var ignored = DurableSamplingDecision.openScope(intent)) {
            return idGenerator.startSpan(spanBuilder, traceId, spanId);
        }
    }

    /**
     * Adds a link to the Workflow span, if one is set, for execution-level correlation. Uses the deterministic Workflow
     * context (the recording span is deferred to the terminal invocation, but shares this span ID).
     */
    private void addWorkflowLink(SpanBuilder spanBuilder) {
        var workflowContext = workflowSpanContext;
        if (workflowContext != null) {
            spanBuilder.addLink(workflowContext);
        }
    }

    /**
     * Links a continuation or replay operation span back to the initial logical operation span, whose ID is
     * deterministic on the execution trace, so the segments of one logical operation stay correlated across
     * invocations.
     */
    private void addInitialOperationLink(SpanBuilder spanBuilder, String operationId) {
        var trace = executionTrace;
        if (trace == null || operationId == null) {
            return;
        }
        var initial = SpanContext.create(
                trace.traceId(),
                idGenerator.generateSpanIdForOperation(durableExecutionArn, operationId),
                effectiveTraceFlags(),
                effectiveTraceState());
        spanBuilder.addLink(initial);
    }

    private TraceFlags effectiveTraceFlags() {
        var invocation = invocationSpan;
        if (invocation != null) {
            return invocation.getSpanContext().getTraceFlags();
        }
        var trace = executionTrace;
        return trace != null ? trace.flags() : TraceFlags.getDefault();
    }

    private TraceState effectiveTraceState() {
        var invocation = invocationSpan;
        return invocation != null ? invocation.getSpanContext().getTraceState() : TraceState.getDefault();
    }

    private static boolean isTerminal(InvocationEndInfo info) {
        return switch (info.invocationStatus()) {
            case SUCCEEDED, FAILED -> true;
            case PENDING, RETRYING -> false;
        };
    }

    private static String spanName(String type, String subType, String name) {
        if (name != null) {
            return name;
        }
        return subType != null ? subType.toLowerCase() : type.toLowerCase();
    }

    private static String attemptSpanName(String type, String subType, String name, Integer attempt) {
        var base = spanName(type, subType, name);
        if (attempt != null) {
            return base + " attempt " + attempt;
        }
        return base;
    }

    private static String attemptKey(String operationId, Integer attempt) {
        return operationId + "-" + (attempt != null ? attempt : "ctx");
    }
}
