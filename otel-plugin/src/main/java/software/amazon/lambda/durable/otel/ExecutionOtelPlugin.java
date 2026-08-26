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
 * Workflow-rooted OpenTelemetry plugin for the AWS Lambda Durable Execution SDK.
 *
 * <p>This is the Java port of the {@code ExecutionOtelPlugin} in the Python and JavaScript SDKs. It renders the full
 * durable-execution hierarchy:
 *
 * <ul>
 *   <li><b>Workflow span</b> — one <em>logical</em> span per durable execution, parented onto the execution ancestor so
 *       it shares the execution trace. Its span ID is derived deterministically from the execution ARN, so every
 *       invocation of the same execution produces the same ID. It is ended (and therefore exported) exactly once, on
 *       the terminal invocation.
 *   <li><b>Invocation span</b> — one per Lambda invocation, a child of the ambient Lambda span when available and a
 *       root otherwise. Created and ended every invocation.
 *   <li><b>Operation span</b> — parented to its parent operation span (or the Workflow span) and carrying a
 *       <em>link</em> to the current Invocation span for correlation. Deterministic ID keyed by operation ID, so a
 *       suspended-then-resumed operation stitches into a single logical span across invocations.
 *   <li><b>Attempt span</b> — one per user-function execution (step attempt, child-context run), child of the operation
 *       span, linked to the current Invocation span.
 * </ul>
 *
 * <p>Contrast with {@link InvocationOtelPlugin} (the invocation-rooted variant, equivalent to the reference
 * {@code InvocationOtelPlugin}): there operations hang off the per-invocation span and link to the Workflow span. Here
 * operations hang off the independent Workflow root span, while each operation/attempt links to the invocation that ran
 * it. Both plugins share {@link DeterministicIdGenerator}, {@link ContextExtractor}, {@link SpanAttributes}, and
 * {@link MdcSpanEnricher}.
 *
 * <p>The Workflow and Invocation spans share one execution trace, anchored at the execution ancestor resolved at
 * invocation start: a valid propagated remote server span becomes that ancestor directly, otherwise a synthetic
 * execution root anchors the trace. The trace ID is stable across invocations of the same execution. When using
 * {@link #ExecutionOtelPlugin()}, the plugin resolves the global provider at invocation start. If the OpenTelemetry
 * Java agent is not initialized yet, telemetry is disabled for that entire invocation and provider resolution is
 * retried on the next invocation.
 *
 * <p>Status mapping (parity with the Python/JS references):
 *
 * <ul>
 *   <li>Invocation span: {@code SUCCEEDED}/{@code PENDING} → {@link StatusCode#OK}; {@code FAILED} →
 *       {@link StatusCode#ERROR}; {@code RETRYING} → {@link StatusCode#UNSET}. {@code RETRYING} is left {@code UNSET}
 *       because the plugin interface does not expose whether the invocation/Workflow was STOPPED or TIMED_OUT versus
 *       retried for a transient error, so an ERROR status cannot be asserted reliably.
 *   <li>Workflow span (terminal only): {@code SUCCEEDED} → {@link StatusCode#OK}; {@code FAILED} →
 *       {@link StatusCode#ERROR}. Non-terminal statuses ({@code PENDING}/{@code RETRYING}) never end the Workflow span,
 *       so it is not exported this invocation (effectively {@link StatusCode#UNSET}).
 * </ul>
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for span/scope storage since the SDK runs user code on multiple
 * threads.
 */
public class ExecutionOtelPlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionOtelPlugin.class);

    private volatile SdkTracerProvider sdkTracerProvider;
    private volatile Tracer tracer;
    private final DeterministicIdGenerator idGenerator;
    private final ContextExtractor contextExtractor;
    private final boolean enableMdc;
    private final String workflowSpanName;
    private final String instrumentationName;

    // Per-invocation state
    private volatile boolean tracingEnabled;
    private volatile Span workflowSpan;
    private volatile Span invocationSpan;
    private volatile String durableExecutionArn;

    // Trace ID and flags of the execution trace, published together as one snapshot so readers never pair a trace ID
    // with mismatched flags.
    private volatile ExecutionTrace executionTrace;
    // The execution's single sampling intent for this invocation, computed once at onInvocationStart and attached to
    // every durable span's parent context so DurableSampler applies it (a resolved decision verbatim, or a deferral to
    // its own delegate) without re-invoking the configured sampler per span.
    private volatile DurableSamplingDecision.Intent samplingIntent;

    /** Immutable snapshot of the resolved execution trace, read atomically through a single volatile reference. */
    private record ExecutionTrace(String traceId, TraceFlags flags) {}

    // Thread-safe storage for operation spans (keyed by operationId) — open spans that need ending
    private final ConcurrentHashMap<String, Span> operationSpans = new ConcurrentHashMap<>();

    // Thread-safe storage for attempt spans/scopes (keyed by operationId + "-" + attempt)
    private final ConcurrentHashMap<String, Span> attemptSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scope> attemptScopes = new ConcurrentHashMap<>();

    // Store operation span contexts for parent resolution (keyed by operationId)
    private final ConcurrentHashMap<String, SpanContext> operationContexts = new ConcurrentHashMap<>();

    /**
     * Creates a Workflow-rooted OTel plugin with default settings: X-Ray context extraction, MDC enabled, root span
     * named {@code "Workflow"}.
     *
     * <p>Uses the provided tracer provider builder. For ADOT Java agent usage, prefer {@link #ExecutionOtelPlugin()}
     * with the plugin jar configured through {@code OTEL_JAVAAGENT_EXTENSIONS}.
     *
     * @param tracerProviderBuilder the tracer provider builder (its ID generator will be wrapped)
     */
    public ExecutionOtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder) {
        this(tracerProviderBuilder, OtelPluginConfig.defaults());
    }

    /**
     * Creates a Workflow-rooted OTel plugin with default settings: X-Ray context extraction and MDC enabled.
     *
     * <p>Resolves {@code GlobalOpenTelemetry} at invocation start. If the ADOT Java agent has not initialized it yet,
     * telemetry is disabled for that invocation and resolution is retried on the next invocation.
     */
    public ExecutionOtelPlugin() {
        this(OtelPluginConfig.defaults());
    }

    /**
     * Creates a Workflow-rooted OTel plugin from the given tracer provider builder and configuration.
     *
     * <p>Customers configure exporters and span processors on the builder; all other tunables (context extractor, MDC
     * toggle, Workflow span name, instrumentation scope name) come from {@link OtelPluginConfig}. Use
     * {@link OtelPluginConfig#builder()} for readable, named configuration:
     *
     * <pre>{@code
     * var plugin = new ExecutionOtelPlugin(
     *     SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
     *     OtelPluginConfig.builder().enableMdc(false).workflowSpanName("Workflow").build());
     * }</pre>
     *
     * @param tracerProviderBuilder the tracer provider builder (its ID generator will be wrapped)
     * @param config the plugin configuration
     */
    public ExecutionOtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder, OtelPluginConfig config) {
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
     * Creates a Workflow-rooted OTel plugin from configuration alone (no caller-supplied tracer provider builder).
     *
     * <p>The config-only constructor uses the ADOT/global provider. Supply a {@code SdkTracerProviderBuilder} via the
     * two-arg constructor for an application-owned provider.
     *
     * @param config the plugin configuration
     */
    public ExecutionOtelPlugin(OtelPluginConfig config) {
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

        // Resolve the one execution ancestor both spans parent onto, so they share a stable-per-execution trace and a
        // sampling decision.
        var extracted = contextExtractor.extract();
        var canonicalTraceId =
                ExecutionTraceContext.canonicalTraceId(extracted, arn(), info.executionStartTime(), idGenerator);
        // Resolve the execution's sampling decision once for this invocation as a full SamplingResult, then apply it to
        // every durable span via DurableSampler. The execution ancestor's trace flags are derived from the same
        // decision so a parent-based sampler stays consistent with it.
        var decision = OtelPluginSupport.resolveSamplingResult(
                sdkTracerProvider,
                extracted,
                Span.current(),
                canonicalTraceId,
                workflowSpanName,
                Attributes.of(DURABLE_EXECUTION_ARN, arn()));
        // A null decision is unresolved on the agent path: defer to DurableSampler's own delegate (keyed by trace ID),
        // rather than fabricating a decision that would bypass an installed drop/rate-limit policy.
        samplingIntent = decision != null
                ? DurableSamplingDecision.Intent.resolved(decision)
                : DurableSamplingDecision.Intent.deferred(canonicalTraceId);
        var sampled = OtelPluginSupport.isSampled(decision);
        var execCtx = ExecutionTraceContext.resolve(extracted, canonicalTraceId, arn(), idGenerator, () -> sampled);
        executionTrace = new ExecutionTrace(canonicalTraceId, execCtx.traceFlags());

        // Workflow root span — parented onto the execution ancestor so it joins the execution trace, with a
        // deterministic span ID from the ARN. Recreated every invocation with the same ID so it is exported once as a
        // single logical span (on the terminal invocation only). Its start time is the backend execution start time.
        var workflowSpanBuilder = tracer.spanBuilder(workflowSpanName)
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(withDurableDecision(Context.root().with(Span.wrap(execCtx.executionAncestor()))))
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setStartTimestamp(info.executionStartTime());
        var workflowSpanId = idGenerator.generateWorkflowSpanId(info.durableExecutionArn());
        // Force the span ID only; the trace ID comes from the parent so the Workflow span joins the execution trace.
        workflowSpan = startDurableSpan(workflowSpanBuilder, null, workflowSpanId);

        // Invocation span — child of the ambient Lambda span when it is on the execution trace, otherwise a child of
        // the execution ancestor so it stays within the same trace.
        var invocationParent = invocationParentContext(execCtx, canonicalTraceId);
        var spanBuilder = tracer.spanBuilder("Invocation")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(invocationParent)
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        invocationSpan = startDurableSpan(spanBuilder);

        // Inject MDC on the handler thread so handler-level logs (between steps) have trace context.
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

        // Clear invocation-level MDC
        if (enableMdc) {
            MdcSpanEnricher.clear();
        }

        // Reset per-invocation operation state WITHOUT ending open operation spans. Matching the JS/Python
        // ExecutionOtelPlugin, an operation span is only ended in onOperationEnd. An operation still open when the
        // invocation suspends is left un-exported here and is re-materialized once (with its deterministic span ID,
        // plus a link to the invocation that completes it) when onOperationEnd fires in a later invocation.
        operationSpans.clear();
        operationContexts.clear();

        // Defensively close any lingering attempt scopes so OTel context is not leaked on worker threads (normally
        // every onUserFunctionStart is paired with onUserFunctionEnd within the invocation). The attempt spans
        // themselves are left un-ended rather than force-ended, consistent with not ending open spans here.
        for (var scope : attemptScopes.values()) {
            scope.close();
        }
        attemptScopes.clear();
        attemptSpans.clear();

        // End the invocation span every invocation.
        if (invocationSpan != null) {
            invocationSpan.setAttribute(
                    DURABLE_INVOCATION_STATUS, info.invocationStatus().name());
            applyInvocationStatus(invocationSpan, info);
            invocationSpan.end();
            invocationSpan = null;
        }
        samplingIntent = null;

        // End the Workflow span only on a terminal status, so it is exported exactly once per execution.
        if (workflowSpan != null) {
            if (isTerminal(info)) {
                workflowSpan.setAttribute(
                        DURABLE_EXECUTION_STATUS, info.invocationStatus().name());
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
            // Non-terminal (PENDING/RETRYING): leave the Workflow span un-ended (not exported this invocation).
            workflowSpan = null;
        }

        // Flush spans before Lambda freezes
        if (sdkTracerProvider != null) {
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
                .setAttribute(DURABLE_OPERATION_TYPE, info.type());
        addInvocationLink(spanBuilder);

        if (info.startTimestamp() != null) {
            spanBuilder.setStartTimestamp(info.startTimestamp());
        }
        if (info.name() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
        }
        if (info.subType() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
        }

        var operationSpanId = idGenerator.generateSpanIdForOperation(durableExecutionArn, info.id());
        var span = startDurableSpan(spanBuilder, null, operationSpanId);

        // Store the open span — will be ended in onOperationEnd or onInvocationEnd
        operationSpans.put(info.id(), span);
        operationContexts.put(info.id(), span.getSpanContext());
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
            endSpan(span, info.endTimestamp());
        } else {
            // Operation completed between invocations: its onOperationStart ran in a prior invocation, whose
            // in-memory span was dropped un-exported at that invocation's end. Emit the operation's single span
            // now, using its deterministic span ID (stable across the execution), plus a link to the invocation
            // that completed it.
            operationContexts.remove(info.id());

            var parentContext = resolveParentContext(info.parentId());

            var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                    .setParent(parentContext)
                    .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                    .setAttribute(DURABLE_OPERATION_ID, info.id())
                    .setAttribute(DURABLE_OPERATION_TYPE, info.type());
            addInvocationLink(spanBuilder);

            if (info.startTimestamp() != null) {
                spanBuilder.setStartTimestamp(info.startTimestamp());
            }
            if (info.name() != null) {
                spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
            }
            if (info.subType() != null) {
                spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
            }

            var operationSpanId = idGenerator.generateSpanIdForOperation(durableExecutionArn, info.id());
            var continuationSpan = startDurableSpan(spanBuilder, null, operationSpanId);

            if (info.status() != null) {
                continuationSpan.setAttribute(DURABLE_OPERATION_STATUS, info.status());
            }
            // Total attempts for retriable operations (STEP, WAIT_FOR_CONDITION) — emitted only at end.
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

            endSpan(continuationSpan, info.endTimestamp());
        }
    }

    // ─── User function hooks ─────────────────────────────────────────────

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        if (!tracingEnabled) return;

        // Skip attempt spans for CONTEXT operations — they are a scoping construct, not a retriable unit of work. Still
        // make the operation span current so auto-instrumented calls become children.
        if ("CONTEXT".equals(info.type())) {
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

        // Parent the attempt span to its operation span.
        var parentContext = resolveParentContext(info.id());

        var spanBuilder = tracer.spanBuilder(attemptSpanName(info.type(), info.subType(), info.name(), info.attempt()))
                .setParent(parentContext)
                .setStartTimestamp(info.startTimestamp() != null ? info.startTimestamp() : Instant.now());
        addInvocationLink(spanBuilder);

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

        endSpan(span, info.endTimestamp());
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
            var setup = OtelPluginSupport.tryResolveGlobalProvider(instrumentationName, "ExecutionOtelPlugin");
            if (setup == null) {
                return false;
            }
            sdkTracerProvider = setup.sdkTracerProvider();
            tracer = setup.tracer();
            return true;
        }
    }

    private void applyInvocationStatus(Span span, InvocationEndInfo info) {
        // Invocation span status mapping:
        //   SUCCEEDED, PENDING -> OK
        //   FAILED             -> ERROR (records the execution error when present)
        //   RETRYING           -> UNSET (left unset)
        // RETRYING is left UNSET on purpose: the plugin interface does not expose whether the invocation/workflow
        // was STOPPED or TIMED_OUT versus retried for a transient error, so an ERROR status cannot be asserted
        // reliably for a retrying invocation.
        switch (info.invocationStatus()) {
            case SUCCEEDED, PENDING -> span.setStatus(StatusCode.OK);
            case FAILED -> {
                var message =
                        info.executionError() != null ? info.executionError().getMessage() : null;
                span.setStatus(StatusCode.ERROR, message);
                if (info.executionError() != null) {
                    span.recordException(info.executionError());
                }
            }
            case RETRYING -> {
                // UNSET — see note above.
            }
        }
    }

    private static boolean isTerminal(InvocationEndInfo info) {
        return switch (info.invocationStatus()) {
            case SUCCEEDED, FAILED -> true;
            case PENDING, RETRYING -> false;
        };
    }

    private String arn() {
        return durableExecutionArn;
    }

    /**
     * The parent context for the Invocation span: the active ambient span when it is already on the execution trace,
     * otherwise the execution ancestor so the Invocation span stays within the same trace.
     */
    private Context invocationParentContext(ExecutionTraceContext execCtx, String canonicalTraceId) {
        var ambient = Span.current().getSpanContext();
        if (ambient.isValid() && ambient.getTraceId().equals(canonicalTraceId)) {
            return withDurableDecision(Context.root().with(Span.current()));
        }
        return withDurableDecision(Context.root().with(Span.wrap(execCtx.executionAncestor())));
    }

    /** Adds a link to the current invocation span, if one exists, for correlation. */
    private void addInvocationLink(SpanBuilder spanBuilder) {
        var currentInvocationSpan = invocationSpan;
        if (currentInvocationSpan != null) {
            spanBuilder.addLink(currentInvocationSpan.getSpanContext());
        }
    }

    private Context resolveParentContext(String parentId) {
        // A parent operation from a prior invocation is anchored by its deterministic span ID on the execution trace.
        // This requires the execution trace to be resolved; it always is when tracing is enabled (executionTrace is set
        // before tracingEnabled in onInvocationStart), but guard defensively so a null trace never reaches
        // SpanContext.create, which would produce an invalid context. Without the trace, fall through to the Workflow
        // span so the operation still hangs off the execution trace.
        var trace = executionTrace;
        if (parentId != null) {
            var parentSpanContext = operationContexts.get(parentId);
            if (parentSpanContext != null) {
                return withDurableDecision(Context.current().with(Span.wrap(parentSpanContext)));
            }
            if (trace != null) {
                // Parent operation from a prior invocation — non-recording placeholder with its deterministic ID.
                var deterministicParentSpanId = idGenerator.generateSpanIdForOperation(durableExecutionArn, parentId);
                var placeholderContext = SpanContext.create(
                        trace.traceId(), deterministicParentSpanId, trace.flags(), TraceState.getDefault());
                return withDurableDecision(Context.current().with(Span.wrap(placeholderContext)));
            }
        }
        // No usable parent operation — hang off the Workflow root span.
        if (workflowSpan != null) {
            return withDurableDecision(Context.current().with(workflowSpan));
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

    private static void endSpan(Span span, Instant endTimestamp) {
        if (endTimestamp != null) {
            span.end(endTimestamp);
        } else {
            span.end();
        }
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
