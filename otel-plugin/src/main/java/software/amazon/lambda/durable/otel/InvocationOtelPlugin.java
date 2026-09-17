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
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
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
 * <p><b>Lifetime.</b> One instance serves exactly one Lambda invocation: {@link #factory()} and its overloads return a
 * {@link DurableExecutionPluginFactory} that the SDK calls once per invocation, and the instance is dropped when the
 * invocation returns. Everything about the invocation — the execution ARN, the resolved execution trace and ancestor,
 * the sampling intent, the Invocation span, the deferred Workflow span context — is therefore a {@code final} field,
 * resolved in the constructor from the {@link InvocationInfo} the factory receives (the same instance
 * {@link #onInvocationStart(InvocationInfo)} then receives). Nothing is reset between invocations because nothing is
 * carried between them.
 *
 * <p>What belongs to the execution environment stays in the factory's {@link OtelPluginEnvironment}: the configuration,
 * the ID generator, and either the application-owned tracer provider (built once) or the lazily resolved ADOT global
 * provider. On the agent path, an invocation whose instance cannot resolve the global provider emits no telemetry at
 * all, and the next invocation's instance resolves it again.
 *
 * <p><b>X-Ray console limitation:</b> In the X-Ray "Segments Timeline" ungrouped view, the plugin's spans (Invocation,
 * operation, attempt) do not appear as nested subsegments of the Lambda platform segment. This is a known limitation of
 * the OTLP-to-X-Ray conversion: the ADOT collector cannot attach OTLP-exported spans as subsegments of the Lambda
 * service's native X-Ray segment because that segment is created outside the OTLP pipeline. Use the "Group by nodes"
 * view to inspect parent-child relationships within the shared execution trace and the links between operation spans
 * and the Workflow span.
 *
 * <p>Thread-safe within its invocation: the SDK runs user code on multiple threads, so the open-span registries are
 * {@link ConcurrentHashMap}s. The invocation's identity needs no such protection — it is final state written before the
 * SDK publishes the instance to those threads.
 */
public final class InvocationOtelPlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(InvocationOtelPlugin.class);

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

    private final SpanContext executionAncestor;

    private final Span invocationSpan;

    /** Deferred Workflow placeholder; the recording span is emitted only on terminal invocation. */
    private final SpanContext workflowSpanContext;

    /**
     * Set when this invocation ends; never cleared, because an instance is never reused. Read by the operation and user
     * function hooks, which may run on other threads of this invocation, so that a straggler hook arriving after the
     * spans have been ended does not open a new one — volatile for that publication.
     */
    private volatile boolean ended;

    /** Immutable snapshot of the resolved execution trace. */
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
     * Returns a factory that creates one plugin instance per invocation against the ADOT Java agent's global provider,
     * with default settings: X-Ray context extraction and MDC enabled.
     *
     * <pre>{@code
     * DurableConfig.builder().withPlugins(InvocationOtelPlugin.factory()).build();
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
        return info -> new InvocationOtelPlugin(environment, info);
    }

    /**
     * Returns a factory that creates one plugin instance per invocation against an application-owned tracer provider,
     * with default settings: X-Ray context extraction and MDC enabled.
     *
     * <p>Customers configure exporters and span processors on the builder — the plugin handles ID generation. The
     * provider is built once, here, and shared by every invocation's instance:
     *
     * <pre>{@code
     * var exporter = LoggingSpanExporter.create();
     * var factory = InvocationOtelPlugin.factory(
     *     SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)));
     * }</pre>
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
     * var factory = InvocationOtelPlugin.factory(
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
        return info -> new InvocationOtelPlugin(environment, info);
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
    private InvocationOtelPlugin(OtelPluginEnvironment environment, InvocationInfo info) {
        var config = environment.config();
        this.idGenerator = environment.idGenerator();
        this.enableMdc = config.enableMdc();
        this.workflowSpanName = config.workflowSpanName();
        this.durableExecutionArn = info.durableExecutionArn();
        this.executionStartTime = info.executionStartTime();

        var setup = environment.bind("InvocationOtelPlugin");
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

        var extracted = config.contextExtractor().extract();

        // Resolve the execution ancestor the Workflow span parents onto so it joins the stable-per-execution trace.
        var canonicalTraceId =
                ExecutionTraceContext.canonicalTraceId(extracted, durableExecutionArn, executionStartTime, idGenerator);
        // Resolve the execution's sampling decision once for this invocation as a full SamplingResult, then apply it to
        // every durable span via DurableSampler (see below). The execution ancestor's trace flags are derived from the
        // same decision so a parent-based sampler stays consistent with it.
        var decision = OtelPluginSupport.resolveSamplingResult(
                sdkTracerProvider,
                extracted,
                Span.current(),
                canonicalTraceId,
                workflowSpanName,
                Attributes.of(DURABLE_EXECUTION_ARN, durableExecutionArn));
        // A null decision is unresolved on the agent path: defer to DurableSampler's own delegate (keyed by trace ID),
        // rather than fabricating a decision that would bypass an installed drop/rate-limit policy.
        this.samplingIntent = decision != null
                ? DurableSamplingDecision.Intent.resolved(decision)
                : DurableSamplingDecision.Intent.deferred(canonicalTraceId);
        var sampled = OtelPluginSupport.isSampled(decision);
        var execCtx = ExecutionTraceContext.resolve(
                extracted, canonicalTraceId, durableExecutionArn, idGenerator, () -> sampled);
        this.executionTrace = new ExecutionTrace(canonicalTraceId, execCtx.traceFlags());
        this.executionAncestor = execCtx.executionAncestor();

        // Invocation span parent — the same-trace ambient span when available, then the execution ancestor, so the
        // Invocation span stays on the execution trace.
        var parentContext = invocationParentContext(execCtx, canonicalTraceId);

        // Create an INTERNAL span for the invocation.
        var spanBuilder = tracer.spanBuilder("Invocation")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        this.invocationSpan = startDurableSpan(spanBuilder);

        // Defer the recording Workflow span until terminal completion. The placeholder uses the Invocation span's
        // resolved sampling metadata so operation links match the span that is eventually exported.
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
        // This invocation's identity and its Invocation span were resolved in the constructor, from the very
        // InvocationInfo this hook receives. What is left is the MDC injection, which belongs here because it must run
        // on the handler thread — the same thread as the context.getLogger() calls in the handler — so handler-level
        // logs between steps carry trace context.
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

        // Clear invocation-level MDC (set in onInvocationStart on the handler thread)
        if (enableMdc) {
            MdcSpanEnricher.clear();
        }

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
        if (disabled()) return;
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
        if (disabled()) return;
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
        if (disabled()) return;

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
        if (disabled()) return;

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

    /**
     * True when this instance emits no telemetry: either the tracer could not be bound for this invocation, or the
     * invocation has already ended and its spans are closed.
     */
    private boolean disabled() {
        return invocationSpan == null || ended;
    }

    private void endOpenSpansChildFirst() {
        // Attempt spans are children of operation spans, so release their scopes and end them first.
        for (var scope : attemptScopes.values()) {
            scope.close();
        }
        for (var span : attemptSpans.values()) {
            span.end();
        }

        // End still-open operation spans with the STARTED status set in onOperationStart.
        // A later invocation's onOperationEnd emits a continuation span with the real terminal status.
        String operationId;
        while ((operationId = operationStartOrder.pollLast()) != null) {
            var span = operationSpans.remove(operationId);
            if (span != null) {
                span.end();
            }
        }
        // The registries are not emptied afterwards: every span they held has been ended above, and this instance is
        // dropped when the invocation returns, so there is nothing to recycle them for.
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
        return invocationSpan.getSpanContext().getTraceFlags();
    }

    private TraceState effectiveTraceState() {
        return invocationSpan.getSpanContext().getTraceState();
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
