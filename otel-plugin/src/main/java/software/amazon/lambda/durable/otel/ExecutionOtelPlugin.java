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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *       ended (and therefore exported) exactly once, on the terminal invocation.
 *   <li><b>Invocation span</b> — one per Lambda invocation, a child of the Workflow span. Created and ended every
 *       invocation.
 *   <li><b>Operation span</b> — parented to its parent operation span (or the Workflow span) and carrying a
 *       <em>link</em> to the current Invocation span for correlation. Deterministic ID keyed by operation ID, so a
 *       suspended-then-resumed operation stitches into a single logical span across invocations.
 *   <li><b>Attempt span</b> — one per user-function execution (step attempt, child-context run), child of the operation
 *       span, linked to the current Invocation span.
 * </ul>
 *
 * <p>Contrast with {@link InvocationOtelPlugin} (the invocation-rooted variant, equivalent to the reference
 * {@code InvocationInvocationOtelPlugin}): there the invocation span is the root and there is no Workflow span. Here
 * the Workflow span is the root, operations hang off the Workflow span, and each operation/attempt links to the
 * invocation that ran it. Both plugins share {@link DeterministicIdGenerator}, {@link ContextExtractor},
 * {@link SpanAttributes}, and {@link MdcSpanEnricher}.
 *
 * <p>Trace ID resolution matches {@link InvocationOtelPlugin}: the X-Ray trace ID from {@code _X_AMZN_TRACE_ID} when
 * available (the backend propagates the same Root to all invocations, unifying the trace), else a deterministic trace
 * ID derived from the execution ARN.
 *
 * <p>Status mapping (parity with the Python/JS references):
 *
 * <ul>
 *   <li>Invocation span: {@code SUCCEEDED}/{@code PENDING} → {@link StatusCode#OK}; {@code RETRYING}/{@code FAILED} →
 *       {@link StatusCode#ERROR}.
 *   <li>Workflow span (terminal only): {@code SUCCEEDED} → {@link StatusCode#OK}; {@code FAILED} →
 *       {@link StatusCode#ERROR}. Non-terminal statuses never end the Workflow span, so it is not exported this
 *       invocation.
 * </ul>
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for span/scope storage since the SDK runs user code on multiple
 * threads.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public class ExecutionOtelPlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionOtelPlugin.class);
    private static final String INSTRUMENTATION_NAME = "aws-durable-execution-sdk-java";
    private static final String DEFAULT_WORKFLOW_SPAN_NAME = "Workflow";

    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;
    private final DeterministicIdGenerator idGenerator;
    private final ContextExtractor contextExtractor;
    private final boolean enableMdc;
    private final String workflowSpanName;

    // Per-invocation state
    private volatile Span workflowSpan;
    private volatile Span invocationSpan;
    private volatile String durableExecutionArn;

    // Thread-safe storage for operation spans (keyed by operationId) — open spans that need ending
    private final ConcurrentHashMap<String, Span> operationSpans = new ConcurrentHashMap<>();

    // Thread-safe storage for attempt spans/scopes (keyed by operationId + "-" + attempt)
    private final ConcurrentHashMap<String, Span> attemptSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scope> attemptScopes = new ConcurrentHashMap<>();

    // Store operation span contexts for parent resolution (keyed by operationId)
    private final ConcurrentHashMap<String, SpanContext> operationContexts = new ConcurrentHashMap<>();

    /**
     * Creates a workflow-rooted OTel plugin with default settings: X-Ray context extraction, MDC enabled, root span
     * named {@code "Workflow"}.
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     */
    public ExecutionOtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder) {
        this(tracerProviderBuilder, new XRayContextExtractor(), true, DEFAULT_WORKFLOW_SPAN_NAME);
    }

    /**
     * Creates a workflow-rooted OTel plugin with a custom context extractor, MDC enabled, root span named
     * {@code "Workflow"}.
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     * @param contextExtractor extracts parent trace context from the Lambda environment
     */
    public ExecutionOtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder, ContextExtractor contextExtractor) {
        this(tracerProviderBuilder, contextExtractor, true, DEFAULT_WORKFLOW_SPAN_NAME);
    }

    /**
     * Creates a workflow-rooted OTel plugin with full configuration.
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     * @param contextExtractor extracts parent trace context from the Lambda environment
     * @param enableMdc if true, injects traceId/spanId/traceSampled into SLF4J MDC for log correlation
     * @param workflowSpanName the name for the Workflow root span
     */
    public ExecutionOtelPlugin(
            SdkTracerProviderBuilder tracerProviderBuilder,
            ContextExtractor contextExtractor,
            boolean enableMdc,
            String workflowSpanName) {
        this.idGenerator = new DeterministicIdGenerator();
        this.tracerProvider = tracerProviderBuilder.setIdGenerator(idGenerator).build();
        this.tracer = tracerProvider.get(INSTRUMENTATION_NAME);
        this.contextExtractor = contextExtractor;
        this.enableMdc = enableMdc;
        this.workflowSpanName = workflowSpanName != null ? workflowSpanName : DEFAULT_WORKFLOW_SPAN_NAME;
    }

    // ─── Invocation hooks ────────────────────────────────────────────────

    @Override
    public void onInvocationStart(InvocationInfo info) {
        this.durableExecutionArn = info.durableExecutionArn();

        // Set execution ARN for deterministic span/trace ID generation
        idGenerator.setDurableExecutionArn(info.durableExecutionArn());

        // Extract trace context from environment (X-Ray header). Only the trace ID is used — the Workflow span is a
        // true root, so the X-Ray parent span ID is intentionally not used for parenting (unlike InvocationOtelPlugin).
        var extractedContext = contextExtractor.extract();
        if (extractedContext != null) {
            idGenerator.setExtractedTraceId(extractedContext.traceId());
        }

        // Workflow root span — deterministic span ID from the ARN, no parent. Recreated every invocation with the
        // same ID so it is exported once as a single logical span (on the terminal invocation only).
        idGenerator.setNextSpanId(idGenerator.generateWorkflowSpanId());
        workflowSpan = tracer.spanBuilder(workflowSpanName)
                .setSpanKind(SpanKind.INTERNAL)
                .setNoParent()
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .startSpan();

        // Invocation span — child of the Workflow span, INTERNAL kind, random span ID (new every invocation).
        var spanBuilder = tracer.spanBuilder("invocation")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(Context.root().with(workflowSpan))
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        invocationSpan = spanBuilder.startSpan();
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
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
        var flushResult = tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        if (!flushResult.isSuccess()) {
            logger.warn("OTel span flush failed or timed out — some spans may be lost");
        }
    }

    // ─── Operation hooks ─────────────────────────────────────────────────

    @Override
    public void onOperationStart(OperationInfo info) {
        if (info.id() == null) return;

        var parentContext = resolveParentContext(info.parentId());

        // Always use a deterministic span ID keyed by operation ID (regardless of replay) so a suspended-then-resumed
        // operation stitches into a single logical span across invocations.
        idGenerator.setNextSpanOperationId(info.id());

        var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                .setAttribute(DURABLE_OPERATION_ID, info.id())
                .setAttribute(DURABLE_OPERATION_TYPE, info.type());
        addInvocationLink(spanBuilder);

        if (info.name() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
        }
        if (info.subType() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
        }

        var span = spanBuilder.startSpan();

        // Store the open span — will be ended in onOperationEnd or onInvocationEnd
        operationSpans.put(info.id(), span);
        operationContexts.put(info.id(), span.getSpanContext());
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        if (info.id() == null) return;

        var span = operationSpans.remove(info.id());

        if (span != null) {
            // Operation was started in this invocation — end normally
            if (info.status() != null) {
                span.setAttribute(DURABLE_OPERATION_STATUS, info.status());
            }
            if (info.error() != null) {
                span.setStatus(StatusCode.ERROR, info.error().getMessage());
                span.recordException(info.error());
            }
            endSpan(span, info.endTimestamp());
        } else {
            // Operation completed between invocations (started in a prior invocation). Recreate it with the same
            // deterministic span ID so it stitches to the original, plus a link to the current invocation.
            operationContexts.remove(info.id());
            idGenerator.setNextSpanOperationId(info.id());

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

            var continuationSpan = spanBuilder.startSpan();

            if (info.status() != null) {
                continuationSpan.setAttribute(DURABLE_OPERATION_STATUS, info.status());
            }
            if (info.error() != null) {
                continuationSpan.setStatus(StatusCode.ERROR, info.error().getMessage());
                continuationSpan.recordException(info.error());
            }

            endSpan(continuationSpan, info.endTimestamp());
        }
    }

    // ─── User function hooks ─────────────────────────────────────────────

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
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
        var key = attemptKey(info.id(), info.attempt());

        // Close scope first (must happen on same thread as makeCurrent)
        var scope = attemptScopes.remove(key);
        if (scope != null) {
            scope.close();
        }

        if (enableMdc) {
            MdcSpanEnricher.clear();
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
        }

        endSpan(span, info.endTimestamp());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void applyInvocationStatus(Span span, InvocationEndInfo info) {
        switch (info.invocationStatus()) {
            case SUCCEEDED, PENDING -> span.setStatus(StatusCode.OK);
            case RETRYING, FAILED -> {
                var message =
                        info.executionError() != null ? info.executionError().getMessage() : null;
                span.setStatus(StatusCode.ERROR, message);
                if (info.executionError() != null) {
                    span.recordException(info.executionError());
                }
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
            var deterministicParentSpanId = idGenerator.generateSpanIdForOperation(parentId);
            var traceId = idGenerator.generateTraceId();
            var placeholderContext = SpanContext.create(
                    traceId, deterministicParentSpanId, TraceFlags.getSampled(), TraceState.getDefault());
            return Context.current().with(Span.wrap(placeholderContext));
        }
        // No parent operation — hang off the Workflow root span.
        if (workflowSpan != null) {
            return Context.current().with(workflowSpan);
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
}
