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
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
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
 *       invocation of the same execution produces the same ID. It is started and ended together (and therefore
 *       exported) exactly once, on the terminal invocation; between invocations it is represented only by a
 *       deterministic {@link SpanContext} that operations parent onto, so no open span is left abandoned.
 *   <li><b>Invocation span</b> — one per Lambda invocation, a child of the ambient Lambda span when available and a
 *       root otherwise. Created and ended every invocation.
 *   <li><b>Operation span</b> — parented to its parent operation span (or the Workflow span) and carrying a
 *       <em>link</em> to the current Invocation span for correlation. Deterministic ID keyed by operation ID, so a
 *       suspended-then-resumed operation stitches into a single logical span across invocations. Started and ended
 *       together in {@code onOperationEnd}, so a suspended operation never leaves an open span.
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
 * execution root anchors the trace. The trace ID is stable across invocations of the same execution, because it is
 * derived from the execution ARN and start time rather than carried in the plugin.
 *
 * <p><b>Lifetime.</b> One instance serves exactly one Lambda invocation: {@link #factory()} and its overloads return a
 * {@link DurableExecutionPluginFactory} that the SDK calls once per invocation, and the instance is dropped when the
 * invocation returns. Everything about the invocation — the execution ARN, the resolved execution trace and ancestor,
 * the sampling intent, the Invocation span, the deferred Workflow span context — is therefore a {@code final} field,
 * resolved in the constructor from the {@link InvocationInfo} the factory receives. Nothing is reset between
 * invocations because nothing is carried between them.
 *
 * <p>What belongs to the execution environment stays in the factory's {@link OtelPluginEnvironment}: the configuration,
 * the ID generator, and either the application-owned tracer provider (built once) or the lazily resolved ADOT global
 * provider. An invocation whose instance cannot resolve the global provider emits no telemetry at all, and the next
 * invocation's instance resolves it again.
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
 * <p><b>Deferred operation spans and context operations.</b> An operation span is created only when the operation
 * completes ({@code onOperationEnd}), so each operation is exported once even across suspend/resume. Before completion
 * it is represented by a deterministic, non-recording {@link SpanContext}. A CONTEXT operation makes this placeholder
 * current, so {@code Span.current()} enrichment is not recorded on the final operation span. The placeholder uses the
 * Invocation span's resolved sampling metadata when available.
 *
 * <p>Thread-safe within its invocation: the SDK runs user code on multiple threads, so the open-span registries are
 * {@link ConcurrentHashMap}s. The invocation's identity needs no such protection — it is final state written before the
 * SDK publishes the instance to those threads.
 */
public final class ExecutionOtelPlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionOtelPlugin.class);

    // ─── Environment lifetime (shared with every other invocation's instance) ─────────────

    private final DeterministicIdGenerator idGenerator;
    private final boolean enableMdc;
    private final String workflowSpanName;

    // ─── This invocation, all resolved in the constructor from its InvocationInfo ─────────

    /** The provider used to flush before Lambda freezes; null when it is not visible to the application. */
    private final SdkTracerProvider sdkTracerProvider;

    /** Null when telemetry is disabled for this invocation, which makes every hook on this instance a no-op. */
    private final Tracer tracer;

    private final String durableExecutionArn;
    private final Instant executionStartTime;

    /** Trace ID and flags of the execution trace, resolved together so they can never be paired mismatched. */
    private final ExecutionTrace executionTrace;

    /**
     * The execution's single sampling intent for this invocation, resolved once and attached to every durable span's
     * parent context so DurableSampler applies it (a resolved decision verbatim, or a deferral to its own delegate)
     * without re-invoking the configured sampler per span.
     */
    private final DurableSamplingDecision.Intent samplingIntent;

    private final Span invocationSpan;

    /**
     * The Workflow span exists as a deterministic context that operations parent onto; the recording span is started
     * and ended in a single call on the terminal invocation, so it is never left open. The execution ancestor and start
     * time are held so that span can be built at invocation end.
     */
    private final SpanContext workflowSpanContext;

    private final SpanContext executionAncestor;

    /**
     * Set when this invocation ends; never cleared, because an instance is never reused. Read by the operation and user
     * function hooks, which may run on other threads of this invocation, so that a straggler hook arriving after the
     * spans have been ended does not open a new one — volatile for that publication.
     */
    private volatile boolean ended;

    /** Immutable snapshot of the resolved execution trace. */
    private record ExecutionTrace(String traceId, TraceFlags flags) {}

    // Thread-safe storage for attempt spans/scopes (keyed by operationId + "-" + attempt)
    private final ConcurrentHashMap<String, Span> attemptSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scope> attemptScopes = new ConcurrentHashMap<>();

    // Deterministic operation contexts (keyed by operationId), held between start and end so children and attempts can
    // parent onto an operation whose recording span is not created until onOperationEnd.
    private final ConcurrentHashMap<String, SpanContext> operationContexts = new ConcurrentHashMap<>();

    // Start timestamps captured at onOperationStart (keyed by operationId), used when onOperationEnd carries none —
    // virtual map/parallel child contexts report null timestamps at end.
    private final ConcurrentHashMap<String, Instant> operationStartTimes = new ConcurrentHashMap<>();

    /**
     * Returns a factory that creates one plugin instance per invocation against the ADOT Java agent's global provider,
     * with default settings: X-Ray context extraction, MDC enabled, root span named {@code "Workflow"}.
     *
     * <pre>{@code
     * DurableConfig.builder().withPlugins(ExecutionOtelPlugin.factory()).build();
     * }</pre>
     *
     * @return the per-invocation plugin factory to hand to {@code DurableConfig.Builder.withPlugins}
     */
    public static DurableExecutionPluginFactory factory() {
        return factory(OtelPluginConfig.defaults());
    }

    /**
     * Returns a factory that creates one plugin instance per invocation against the ADOT Java agent's global provider.
     *
     * <p>The global provider is resolved when the first invocation's instance needs it. If the agent has not
     * initialized it yet, that invocation emits no telemetry and the next invocation's instance resolves it again.
     *
     * @param config the plugin configuration
     * @return the per-invocation plugin factory to hand to {@code DurableConfig.Builder.withPlugins}
     */
    public static DurableExecutionPluginFactory factory(OtelPluginConfig config) {
        var environment = OtelPluginEnvironment.forGlobalProvider(config);
        return info -> new ExecutionOtelPlugin(environment, info);
    }

    /**
     * Returns a factory that creates one plugin instance per invocation against an application-owned tracer provider,
     * with default settings: X-Ray context extraction, MDC enabled, root span named {@code "Workflow"}.
     *
     * <p>Customers configure exporters and span processors on the builder — the plugin handles ID generation. The
     * provider is built once, here, and shared by every invocation's instance.
     *
     * @param tracerProviderBuilder the tracer provider builder (its ID generator and sampler will be wrapped)
     * @return the per-invocation plugin factory to hand to {@code DurableConfig.Builder.withPlugins}
     */
    public static DurableExecutionPluginFactory factory(SdkTracerProviderBuilder tracerProviderBuilder) {
        return factory(tracerProviderBuilder, OtelPluginConfig.defaults());
    }

    /**
     * Returns a factory that creates one plugin instance per invocation against an application-owned tracer provider.
     *
     * <p>Customers configure exporters and span processors on the builder; all other tunables (context extractor, MDC
     * toggle, Workflow span name, instrumentation scope name) come from {@link OtelPluginConfig}:
     *
     * <pre>{@code
     * var factory = ExecutionOtelPlugin.factory(
     *     SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
     *     OtelPluginConfig.builder().enableMdc(false).workflowSpanName("Workflow").build());
     * }</pre>
     *
     * @param tracerProviderBuilder the tracer provider builder (its ID generator and sampler will be wrapped)
     * @param config the plugin configuration
     * @return the per-invocation plugin factory to hand to {@code DurableConfig.Builder.withPlugins}
     */
    public static DurableExecutionPluginFactory factory(
            SdkTracerProviderBuilder tracerProviderBuilder, OtelPluginConfig config) {
        var environment = OtelPluginEnvironment.forProviderBuilder(tracerProviderBuilder, config);
        return info -> new ExecutionOtelPlugin(environment, info);
    }

    /**
     * Creates the instance that serves one invocation.
     *
     * <p>Everything this invocation's spans are keyed by is resolved here, from the {@code info} the factory received:
     * the tracer binding, the extracted context, the canonical execution trace and its ancestor, the single sampling
     * intent, the Invocation span, and the deferred Workflow span context. Resolving them in the constructor — before
     * the SDK publishes this instance to the operation and user function threads — is what lets them be {@code final}
     * rather than volatile per-invocation state.
     *
     * <p>When the tracer cannot be bound, telemetry is disabled for this invocation: the span fields stay null and
     * every hook returns immediately. The next invocation gets a new instance, which binds again.
     */
    private ExecutionOtelPlugin(OtelPluginEnvironment environment, InvocationInfo info) {
        var config = environment.config();
        this.idGenerator = environment.idGenerator();
        this.enableMdc = config.enableMdc();
        this.workflowSpanName = config.workflowSpanName();
        this.durableExecutionArn = info.durableExecutionArn();
        this.executionStartTime = info.executionStartTime();

        var setup = environment.bind("ExecutionOtelPlugin");
        if (setup == null) {
            this.sdkTracerProvider = null;
            this.tracer = null;
            this.samplingIntent = null;
            this.executionTrace = null;
            this.executionAncestor = null;
            this.invocationSpan = null;
            this.workflowSpanContext = null;
            return;
        }
        this.sdkTracerProvider = setup.sdkTracerProvider();
        this.tracer = setup.tracer();

        // Resolve the one execution ancestor both spans parent onto, so they share a stable-per-execution trace and a
        // sampling decision.
        var extracted = config.contextExtractor().extract();
        var canonicalTraceId =
                ExecutionTraceContext.canonicalTraceId(extracted, arn(), executionStartTime, idGenerator);
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
        this.samplingIntent = decision != null
                ? DurableSamplingDecision.Intent.resolved(decision)
                : DurableSamplingDecision.Intent.deferred(canonicalTraceId);
        var sampled = OtelPluginSupport.isSampled(decision);
        var execCtx = ExecutionTraceContext.resolve(extracted, canonicalTraceId, arn(), idGenerator, () -> sampled);
        this.executionTrace = new ExecutionTrace(canonicalTraceId, execCtx.traceFlags());
        this.executionAncestor = execCtx.executionAncestor();

        // Invocation span — child of the ambient Lambda span when it is on the execution trace, otherwise a child of
        // the execution ancestor so it stays within the same trace.
        var invocationParent = invocationParentContext(execCtx, canonicalTraceId);
        var spanBuilder = tracer.spanBuilder("Invocation")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(invocationParent)
                .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        this.invocationSpan = startDurableSpan(spanBuilder);

        // Defer the recording Workflow span until terminal completion. The placeholder uses the Invocation span's
        // resolved sampling metadata so operation parents/links match the span that is eventually exported.
        var invocationContext = invocationSpan.getSpanContext();
        this.workflowSpanContext = SpanContext.create(
                canonicalTraceId,
                idGenerator.generateWorkflowSpanId(durableExecutionArn),
                invocationContext.getTraceFlags(),
                invocationContext.getTraceState());
    }

    // ─── Invocation hooks ────────────────────────────────────────────────

    @Override
    public void onInvocationStart(InvocationInfo info) {
        // This invocation's identity, its Invocation span and its Workflow span context were resolved in the
        // constructor, from the very InvocationInfo this hook receives. What is left is the MDC injection, which
        // belongs
        // here because it must run on the handler thread so handler-level logs between steps carry trace context.
        if (invocationSpan == null || !enableMdc) {
            return;
        }
        MDC.put(MdcSpanEnricher.MDC_TRACE_ID, invocationSpan.getSpanContext().getTraceId());
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        if (disabled()) {
            return;
        }
        // Set before the spans are ended, so a straggler hook from another thread of this invocation cannot open a span
        // under one that is already closed. Never cleared: this instance serves no second invocation.
        ended = true;

        // Clear invocation-level MDC
        if (enableMdc) {
            MdcSpanEnricher.clear();
        }

        // Release OTel context on worker threads, then end any attempt spans still open so no recording span is
        // abandoned. Attempt spans normally start and end within one user-function call, so this is a safeguard.
        for (var scope : attemptScopes.values()) {
            scope.close();
        }
        for (var span : attemptSpans.values()) {
            span.end();
        }
        // The placeholder and attempt registries are not emptied: an operation that never completed has no recording
        // span to abandon, every attempt span above has been ended, and this instance is dropped when the invocation
        // returns, so there is nothing to recycle them for.

        // End the invocation span every invocation.
        invocationSpan.setAttribute(
                DURABLE_INVOCATION_STATUS, info.invocationStatus().name());
        applyInvocationStatus(invocationSpan, info);
        invocationSpan.end();

        // Materialize the Workflow span only on terminal status.
        if (isTerminal(info)) {
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
        if (disabled()) return;
        if (info.id() == null) return;

        // Retain only a deterministic placeholder. Its flags/state come from the Invocation span's resolved sampling
        // decision because CONTEXT operations make this placeholder current.
        var trace = executionTrace;
        var spanId = idGenerator.generateSpanIdForOperation(durableExecutionArn, info.id());
        operationContexts.put(
                info.id(), SpanContext.create(trace.traceId(), spanId, effectiveTraceFlags(), effectiveTraceState()));

        // Retain the start time for onOperationEnd, which may receive none (virtual FLAT map/parallel operations).
        if (info.startTimestamp() != null) {
            operationStartTimes.put(info.id(), info.startTimestamp());
        }
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        if (disabled()) return;
        if (info.id() == null) return;

        // Start and end the operation's single span here, using its deterministic span ID and linking to the
        // invocation that completed it. This covers operations that ran in this invocation and ones resumed from an
        // earlier one, and it is the only place an operation span is created — so none is ever left open.
        operationContexts.remove(info.id());
        var capturedStart = operationStartTimes.remove(info.id());

        var parentContext = resolveParentContext(info.parentId());

        var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                .setAttribute(DURABLE_OPERATION_ID, info.id())
                .setAttribute(DURABLE_OPERATION_TYPE, info.type());
        addInvocationLink(spanBuilder);

        // Use the earliest known start so the operation span never starts after its own attempt/child spans (which were
        // created earlier, at onUserFunctionStart/onOperationStart). onOperationEnd's start timestamp can be a later
        // re-observed value than the start captured at onOperationStart, so take the minimum of the two.
        var startTimestamp = earliest(capturedStart, info.startTimestamp());
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
        var span = startDurableSpan(spanBuilder, null, operationSpanId);

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
            // extractErrorFromOperation returns null for CANCELLED (always) and for FAILED/TIMED_OUT/STOPPED with no
            // attached error object — those carry a non-null, non-SUCCEEDED status and must stay UNSET. A null status
            // is a successful statusless virtual (FLAT CONTEXT) operation, which is OK.
            span.setStatus(StatusCode.OK);
        }

        endSpan(span, info.endTimestamp());
    }

    // ─── User function hooks ─────────────────────────────────────────────

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        if (disabled()) return;

        // Skip attempt spans for CONTEXT operations — they are a scoping construct, not a retriable unit of work. Still
        // make the operation's context current so auto-instrumented calls become children of the (deferred) operation
        // span. The context is non-recording until onOperationEnd, which is enough for parent propagation.
        if ("CONTEXT".equals(info.type())) {
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
        if (disabled()) return;

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

    /**
     * True when this instance emits no telemetry: either the tracer could not be bound for this invocation, or the
     * invocation has already ended and its spans are closed.
     */
    private boolean disabled() {
        return invocationSpan == null || ended;
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
                        trace.traceId(), deterministicParentSpanId, effectiveTraceFlags(), effectiveTraceState());
                return withDurableDecision(Context.current().with(Span.wrap(placeholderContext)));
            }
        }
        // No usable parent operation — hang off the deferred Workflow span via its deterministic context.
        var workflowContext = workflowSpanContext;
        if (workflowContext != null) {
            return withDurableDecision(Context.current().with(Span.wrap(workflowContext)));
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

    private TraceFlags effectiveTraceFlags() {
        return invocationSpan.getSpanContext().getTraceFlags();
    }

    private TraceState effectiveTraceState() {
        return invocationSpan.getSpanContext().getTraceState();
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

    /** Returns the earlier of two timestamps, ignoring nulls; null only when both are null. */
    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
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
