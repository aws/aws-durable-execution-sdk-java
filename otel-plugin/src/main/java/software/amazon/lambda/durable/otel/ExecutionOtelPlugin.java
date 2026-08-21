// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.common.AttributeKey;
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
 *   <li><b>Workflow span</b> — one <em>logical</em> root span per durable execution. Its span ID is derived
 *       deterministically from the execution ARN, so every invocation of the same execution produces the same ID. It is
 *       started and ended (and therefore exported) exactly once, on the terminal invocation. Between invocations it is
 *       represented by a deterministic {@link SpanContext} that operations parent onto.
 *   <li><b>Invocation span</b> — one per Lambda invocation, a child of the ambient Lambda span when available and a
 *       root otherwise. Created and ended every invocation.
 *   <li><b>Operation span</b> — parented to its parent operation span (or the Workflow span) and carrying a
 *       <em>link</em> to the current Invocation span for correlation. Deterministic ID keyed by operation ID, so a
 *       suspended-then-resumed operation stitches into a single logical span across invocations. Started and ended
 *       together in onOperationEnd.
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
 * <p>The Workflow trace ID is derived from the execution start time and ARN, and is independent of the ambient
 * Lambda/X-Ray trace. Invocation spans inherit the active ambient context, or extracted upstream context as a fallback.
 * When using {@link #ExecutionOtelPlugin()}, the plugin resolves the global provider at invocation start. If the
 * OpenTelemetry Java agent is not initialized yet, telemetry is disabled for that entire invocation and provider
 * resolution is retried on the next invocation.
 *
 * <p>Status mapping (parity with the Python/JS references):
 *
 * <ul>
 *   <li>Invocation span: {@code SUCCEEDED}/{@code PENDING} → {@link StatusCode#OK}; {@code FAILED} →
 *       {@link StatusCode#ERROR}; {@code RETRYING} → {@link StatusCode#UNSET}. {@code RETRYING} is left {@code UNSET}
 *       because the plugin interface does not expose whether the invocation/Workflow was STOPPED or TIMED_OUT versus
 *       retried for a transient error, so an ERROR status cannot be asserted reliably.
 *   <li>Workflow span (terminal only): {@code SUCCEEDED} → {@link StatusCode#OK}; {@code FAILED} →
 *       {@link StatusCode#ERROR}. Non-terminal statuses ({@code PENDING}/{@code RETRYING}) never materialize the
 *       Workflow span, so it is not exported this invocation (effectively {@link StatusCode#UNSET}).
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

    // Between invocations the Workflow span exists only as this deterministic context, which operations parent onto.
    // The recording span is started and ended on the terminal invocation, beginning at executionStartTime.
    private volatile SpanContext workflowSpanContext;
    private volatile Instant executionStartTime;
    private volatile Span invocationSpan;
    private volatile String durableExecutionArn;
    private volatile String workflowTraceId;

    // The Workflow root's sampling decision. Operation and attempt contexts carry these flags so the whole Workflow
    // trace is sampled or dropped together.
    private volatile TraceFlags workflowTraceFlags = TraceFlags.getSampled();

    // Thread-safe storage for attempt spans/scopes (keyed by operationId + "-" + attempt)
    private final ConcurrentHashMap<String, Span> attemptSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scope> attemptScopes = new ConcurrentHashMap<>();

    // Deterministic operation contexts (keyed by operationId), held between start and end so children and attempts can
    // parent onto an operation whose recording span is not created until onOperationEnd.
    private final ConcurrentHashMap<String, SpanContext> operationContexts = new ConcurrentHashMap<>();

    // Start timestamps captured at onOperationStart (keyed by operationId), used when onOperationEnd carries none.
    // Virtual map/parallel child contexts report null timestamps at end.
    private final ConcurrentHashMap<String, Instant> operationStartTimes = new ConcurrentHashMap<>();

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

        // Prefer the active Java-agent span, then fall back to explicitly extracted upstream context.
        var invocationParent = extractCurrentSpanContext();
        if (invocationParent == null) {
            invocationParent = contextExtractor.extract();
        }

        Context parentContext;
        if (invocationParent != null && invocationParent.parentSpanId() != null) {
            var parentSpanContext = SpanContext.createFromRemoteParent(
                    invocationParent.traceId(),
                    invocationParent.parentSpanId(),
                    TraceFlags.getSampled(),
                    TraceState.getDefault());
            parentContext = Context.root().with(Span.wrap(parentSpanContext));
        } else {
            parentContext = Context.root();
        }

        // Invocation span — child of the ambient Lambda span when available, otherwise a root.
        var spanBuilder = tracer.spanBuilder("Invocation")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        invocationSpan = spanBuilder.startSpan();

        // Compute the Workflow root's deterministic IDs and sampling decision so operations can parent onto it before
        // the recording span exists. The Workflow trace is independent of the ambient trace, so operations follow the
        // Workflow root rather than the invocation span; a link to an unsampled invocation may be left unresolved.
        this.executionStartTime = info.executionStartTime();
        workflowTraceId =
                idGenerator.generateTraceIdForExecution(info.durableExecutionArn(), info.executionStartTime());
        var workflowSpanId = idGenerator.generateWorkflowSpanId(info.durableExecutionArn());
        workflowTraceFlags =
                OtelPluginSupport.resolveWorkflowTraceFlags(sdkTracerProvider, workflowTraceId, workflowSpanName);
        workflowSpanContext =
                SpanContext.create(workflowTraceId, workflowSpanId, workflowTraceFlags, TraceState.getDefault());

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

        // Drop per-invocation operation state. An operation still open here carries over and is emitted once by the
        // invocation that completes it.
        operationContexts.clear();
        operationStartTimes.clear();

        // Release OTel context on worker threads, then end any attempt spans still open. Attempt spans normally start
        // and end within a single user-function call, so this is a safeguard.
        for (var scope : attemptScopes.values()) {
            scope.close();
        }
        attemptScopes.clear();
        for (var span : attemptSpans.values()) {
            span.end();
        }
        attemptSpans.clear();

        // End the invocation span every invocation.
        if (invocationSpan != null) {
            invocationSpan.setAttribute(
                    DURABLE_INVOCATION_STATUS, info.invocationStatus().name());
            applyInvocationStatus(invocationSpan, info);
            invocationSpan.end();
            invocationSpan = null;
        }

        // The Workflow span is materialized on a terminal status only: started and ended in the same call so it is
        // exported once per execution. Non-terminal statuses (PENDING/RETRYING) export no Workflow span.
        if (isTerminal(info) && workflowSpanContext != null) {
            var workflowSpanBuilder = tracer.spanBuilder(workflowSpanName)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setNoParent()
                    .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                    .setStartTimestamp(executionStartTime != null ? executionStartTime : Instant.now());
            var workflowSpan = idGenerator.startSpan(
                    workflowSpanBuilder, workflowSpanContext.getTraceId(), workflowSpanContext.getSpanId());
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
        workflowSpanContext = null;
        executionStartTime = null;

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

        // Retain the deterministic context so children and attempts can parent onto the operation. The recording span
        // is created in onOperationEnd, keeping a suspended-then-resumed operation a single logical span.
        var spanId = idGenerator.generateSpanIdForOperation(durableExecutionArn, info.id());
        operationContexts.put(
                info.id(), SpanContext.create(workflowTraceId, spanId, workflowTraceFlags, TraceState.getDefault()));

        // Retain the start time for onOperationEnd, which may receive none.
        if (info.startTimestamp() != null) {
            operationStartTimes.put(info.id(), info.startTimestamp());
        }
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        if (!tracingEnabled) return;
        if (info.id() == null) return;

        // Start and end the operation's span in the same call, using its deterministic span ID and linking to the
        // invocation that completed it. This covers operations that ran in this invocation and ones resumed from an
        // earlier one.
        operationContexts.remove(info.id());
        var capturedStart = operationStartTimes.remove(info.id());

        var parentContext = resolveParentContext(info.parentId());

        var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                .setAttribute(DURABLE_OPERATION_ID, info.id())
                .setAttribute(DURABLE_OPERATION_TYPE, info.type());
        addInvocationLink(spanBuilder);

        // Prefer the end info's start timestamp, falling back to the one captured at operation start.
        var startTimestamp = info.startTimestamp() != null ? info.startTimestamp() : capturedStart;
        if (startTimestamp != null) {
            spanBuilder.setStartTimestamp(startTimestamp);
        }
        if (info.name() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
        }
        if (info.subType() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
        }

        var operationSpanId = idGenerator.generateSpanIdForOperation(durableExecutionArn, info.id());
        var span = idGenerator.startSpan(spanBuilder, null, operationSpanId);

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
    }

    // ─── User function hooks ─────────────────────────────────────────────

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        if (!tracingEnabled) return;

        // Skip attempt spans for CONTEXT operations — they are a scoping construct, not a retriable unit of work. Still
        // make the operation span current so auto-instrumented calls become children.
        if ("CONTEXT".equals(info.type())) {
            // Make the operation's context current so auto-instrumented calls become its children.
            var operationContext = operationContexts.get(info.id());
            if (operationContext != null) {
                var scope = Span.wrap(operationContext).makeCurrent();
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

        var span = spanBuilder.startSpan();
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

        var outcome = info.succeeded() ? "SUCCEEDED" : "FAILED";
        span.setAttribute(DURABLE_ATTEMPT_OUTCOME, outcome);

        if (!info.succeeded() && info.error() != null) {
            span.setStatus(StatusCode.ERROR, info.error().getMessage());
            span.recordException(info.error());
        } else if (info.succeeded()) {
            span.setStatus(StatusCode.OK);
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

    /** Adds a link to the current invocation span, if one exists, for correlation. */
    private void addInvocationLink(SpanBuilder spanBuilder) {
        var currentInvocationSpan = invocationSpan;
        if (currentInvocationSpan != null) {
            spanBuilder.addLink(currentInvocationSpan.getSpanContext());
        }
    }

    private Context resolveParentContext(String parentId) {
        if (parentId != null) {
            var parentSpanContext = operationContexts.get(parentId);
            if (parentSpanContext != null) {
                return Context.current().with(Span.wrap(parentSpanContext));
            }
            // Parent operation from a prior invocation — create a non-recording placeholder with its deterministic ID.
            var deterministicParentSpanId = idGenerator.generateSpanIdForOperation(durableExecutionArn, parentId);
            var placeholderContext = SpanContext.create(
                    workflowTraceId, deterministicParentSpanId, workflowTraceFlags, TraceState.getDefault());
            return Context.current().with(Span.wrap(placeholderContext));
        }
        // No parent operation — hang off the Workflow root span via its deterministic (non-recording) context, whose
        // span ID matches the Workflow span materialized on the terminal invocation.
        if (workflowSpanContext != null) {
            return Context.current().with(Span.wrap(workflowSpanContext));
        }
        return Context.current();
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

    private static ExtractedContext extractCurrentSpanContext() {
        return OtelPluginSupport.extractCurrentSpanContext();
    }
}
