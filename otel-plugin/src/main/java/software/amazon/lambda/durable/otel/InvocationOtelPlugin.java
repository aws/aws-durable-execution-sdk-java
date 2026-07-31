// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * OpenTelemetry plugin for the AWS Lambda Durable Execution SDK.
 *
 * <p>Creates spans at these levels:
 *
 * <ul>
 *   <li><b>Workflow span</b> — one logical span per durable execution (deterministic ID from the ARN, exported once on
 *       the terminal invocation). Operation and attempt spans carry a <em>link</em> to it for execution-level
 *       correlation; they remain parented to the per-invocation span (this plugin is invocation-rooted).
 *   <li><b>Invocation span</b> — one per Lambda invocation
 *   <li><b>Operation span</b> — created when an operation starts, ended when it completes or when the invocation ends
 *   <li><b>Attempt span</b> — one per user function execution (step attempt, child context run)
 * </ul>
 *
 * <p><b>Workflow span behavior by provider:</b>
 *
 * <ul>
 *   <li>When using the ADOT Java agent ({@link #InvocationOtelPlugin()}), the Workflow span appears as a separate root
 *       segment in the X-Ray trace because it uses {@code setNoParent()} with a deterministic span ID. This is expected
 *       — it serves as a correlation anchor across invocations. The Invocation span and its children nest under the
 *       ADOT agent's Lambda segment as subsegments.
 *   <li>When using a custom {@link io.opentelemetry.sdk.trace.SdkTracerProviderBuilder} (no ADOT agent), the Workflow
 *       span is similarly unparented but all spans share the same trace ID. Operation and attempt spans link to the
 *       Workflow span for execution-level correlation.
 * </ul>
 *
 * <p>Trace ID resolution:
 *
 * <ol>
 *   <li>Uses the X-Ray trace ID from {@code _X_AMZN_TRACE_ID} when available. The durable execution backend propagates
 *       the same Root to all invocations of the same execution, naturally unifying the trace.
 *   <li>Falls back to a deterministic trace ID derived from the execution ARN (for local tests or non-Lambda
 *       environments).
 * </ol>
 *
 * <p>Requires the ADOT Lambda Layer for trace export. Configure with:
 *
 * <ul>
 *   <li>Lambda Layer: {@code AWSOpenTelemetryDistroJava} (provides the ADOT Java agent and export pipeline)
 *   <li>Tracing: Active (to populate {@code _X_AMZN_TRACE_ID})
 * </ul>
 *
 * <p>When using {@link #InvocationOtelPlugin()}, the plugin requires
 * {@code OtelPluginAutoConfigurationCustomizerProvider} to have been installed by the OpenTelemetry Java agent and uses
 * the global provider directly.
 *
 * <p><b>X-Ray console limitation:</b> In the X-Ray "Segments Timeline" ungrouped view, the plugin's spans (Invocation,
 * operation, attempt) do not appear as nested subsegments of the Lambda platform segment. This is a known limitation of
 * the OTLP-to-X-Ray conversion: the ADOT collector cannot attach OTLP-exported spans as subsegments of the Lambda
 * service's native X-Ray segment because that segment is created outside the OTLP pipeline. Use the "Group by nodes"
 * view to see the full span hierarchy correctly — it stitches all spans together by trace ID and parent-child
 * relationships regardless of segment boundaries.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for span/scope storage since the SDK runs user code on multiple
 * threads.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public class InvocationOtelPlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(InvocationOtelPlugin.class);
    private static final String INSTRUMENTATION_NAME = "aws-durable-execution-sdk-java";
    private static final String DEFAULT_WORKFLOW_SPAN_NAME = "Workflow";

    private final SdkTracerProvider sdkTracerProvider;
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
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     */
    public InvocationOtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder) {
        this(tracerProviderBuilder, new XRayContextExtractor(), true);
    }

    /**
     * Creates an OTel plugin with default settings: X-Ray context extraction and MDC enabled.
     *
     * <p>Uses {@code GlobalOpenTelemetry} directly and assumes deterministic ID generation was installed by
     * {@code OtelPluginAutoConfigurationCustomizerProvider}.
     */
    public InvocationOtelPlugin() {
        this(getDefaultTracerProvider(), createDefaultIdGenerator());
    }

    /**
     * Creates an OTel plugin with a custom context extractor, MDC enabled.
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     * @param contextExtractor extracts parent trace context from the Lambda environment
     */
    public InvocationOtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder, ContextExtractor contextExtractor) {
        this(tracerProviderBuilder, contextExtractor, true);
    }

    /**
     * Creates an OTel plugin with the given context extractor and MDC setting, using the default Workflow span name.
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     * @param contextExtractor extracts parent trace context from the Lambda environment
     * @param enableMdc if true, injects traceId/spanId/traceSampled into SLF4J MDC for log correlation
     */
    public InvocationOtelPlugin(
            SdkTracerProviderBuilder tracerProviderBuilder, ContextExtractor contextExtractor, boolean enableMdc) {
        this(tracerProviderBuilder, contextExtractor, enableMdc, DEFAULT_WORKFLOW_SPAN_NAME);
    }

    /**
     * Creates an OTel plugin with full configuration.
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     * @param contextExtractor extracts parent trace context from the Lambda environment
     * @param enableMdc if true, injects traceId/spanId/traceSampled into SLF4J MDC for log correlation
     * @param workflowSpanName the name for the Workflow span
     */
    public InvocationOtelPlugin(
            SdkTracerProviderBuilder tracerProviderBuilder,
            ContextExtractor contextExtractor,
            boolean enableMdc,
            String workflowSpanName) {
        this.idGenerator = new DeterministicIdGenerator();

        this.sdkTracerProvider =
                tracerProviderBuilder.setIdGenerator(idGenerator).build();
        this.tracer = sdkTracerProvider.get(INSTRUMENTATION_NAME);
        this.contextExtractor = contextExtractor;
        this.enableMdc = enableMdc;
        this.workflowSpanName = workflowSpanName != null ? workflowSpanName : DEFAULT_WORKFLOW_SPAN_NAME;
    }

    private InvocationOtelPlugin(TracerProvider tracerProvider, DeterministicIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
        this.sdkTracerProvider = getSdkTracerProviderForFlush(tracerProvider);
        this.tracer = tracerProvider.get(INSTRUMENTATION_NAME);

        this.contextExtractor = new XRayContextExtractor();
        this.enableMdc = true;
        this.workflowSpanName = DEFAULT_WORKFLOW_SPAN_NAME;
    }

    // ─── Invocation hooks ────────────────────────────────────────────────

    @Override
    public void onInvocationStart(InvocationInfo info) {
        this.durableExecutionArn = info.durableExecutionArn();

        // Set execution ARN for deterministic span ID generation
        idGenerator.setDurableExecutionArn(info.durableExecutionArn());

        // Extract trace context from environment (X-Ray header)
        var extractedContext = contextExtractor.extract();
        if (extractedContext == null) {
            extractedContext = extractCurrentSpanContext();
        }

        if (extractedContext != null) {
            // Use the X-Ray trace ID — backend propagates same Root across all invocations
            idGenerator.setExtractedTraceId(extractedContext.traceId());
        } else {
            idGenerator.setExtractedTraceId(null);
        }
        // If no extracted context, idGenerator falls back to ARN-derived trace ID

        // Workflow root span — one logical span per durable execution, created unconditionally (independent of the
        // X-Ray parent below). Deterministic span ID from the ARN so it is the same across invocations; exported once,
        // on the terminal invocation. Operation and attempt spans link to it for execution-level correlation while
        // remaining parented to the per-invocation span (this plugin stays invocation-rooted).
        var workflowSpanBuilder = tracer.spanBuilder(workflowSpanName)
                .setSpanKind(SpanKind.INTERNAL)
                .setNoParent()
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setStartTimestamp(info.executionStartTime() != null ? info.executionStartTime() : Instant.now());
        idGenerator.setNextSpanId(idGenerator.generateWorkflowSpanId());
        workflowSpan = workflowSpanBuilder.startSpan();

        // Determine parent context for the invocation span.
        Context parentContext;
        if (extractedContext != null && extractedContext.parentSpanId() != null) {
            // Reconstruct a remote parent from the extracted trace context (X-Ray header or current span).
            // This connects plugin spans to the Lambda service's X-Ray segments.
            var parentSpanContext = SpanContext.createFromRemoteParent(
                    extractedContext.traceId(),
                    extractedContext.parentSpanId(),
                    TraceFlags.getSampled(),
                    TraceState.getDefault());
            parentContext = Context.root().with(Span.wrap(parentSpanContext));
        } else {
            parentContext = Context.root();
        }

        // Create an INTERNAL span for the invocation.
        var spanBuilder = tracer.spanBuilder("Invocation")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        invocationSpan = spanBuilder.startSpan();

        // Inject MDC on the handler thread so handler-level logs (between steps) have trace context.
        // This runs on the same thread as context.getLogger() calls in the handler.
        if (enableMdc) {
            var traceId = idGenerator.generateTraceId();
            MDC.put(MdcSpanEnricher.MDC_TRACE_ID, traceId);
        }
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        // Clear invocation-level MDC (set in onInvocationStart on the handler thread)
        if (enableMdc) {
            MdcSpanEnricher.clear();
        }

        if (invocationSpan == null) return;

        // End still-open operation spans without stamping a status — no terminal
        // durable.operation.status means still running (STARTED). A later invocation's
        // onOperationEnd emits a continuation span with the real terminal status.
        for (var entry : operationSpans.entrySet()) {
            entry.getValue().end();
        }
        operationSpans.clear();
        operationContexts.clear();

        // End any attempt spans that are still open (e.g., crash before onUserFunctionEnd)
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

        // End the Workflow span only on a terminal status, so it is exported exactly once per execution
        // (SUCCEEDED -> OK, FAILED -> ERROR; non-terminal statuses leave it un-ended / not exported this invocation).
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
            workflowSpan = null;
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
        if (info.id() == null) return;

        var parentContext = resolveParentContext(info.parentId());

        var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                .setAttribute(DURABLE_OPERATION_ID, info.id())
                .setAttribute(DURABLE_OPERATION_TYPE, info.type());

        if (info.isReplay()) {
            // Operation was already started in a prior invocation — use a random span ID
            // and add a Link to the deterministic span from the original invocation for correlation.
            var deterministicSpanId = idGenerator.generateSpanIdForOperation(info.id());
            var traceId = idGenerator.generateTraceId();
            var linkedSpanContext =
                    SpanContext.create(traceId, deterministicSpanId, TraceFlags.getSampled(), TraceState.getDefault());
            spanBuilder.addLink(linkedSpanContext);
        } else {
            // First execution — use deterministic span ID so continuations can link back
            idGenerator.setNextSpanOperationId(info.id());
        }

        // Link to the Workflow span for execution-level correlation (operation stays parented to the invocation span).
        addWorkflowLink(spanBuilder);

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
            // Operation was started in a prior invocation — create a continuation span with Link
            // to the deterministic span ID from the original invocation.
            var deterministicSpanId = idGenerator.generateSpanIdForOperation(info.id());
            var traceId = idGenerator.generateTraceId();
            var linkedSpanContext =
                    SpanContext.create(traceId, deterministicSpanId, TraceFlags.getSampled(), TraceState.getDefault());

            var parentContext = resolveParentContext(info.parentId());

            var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                    .setParent(parentContext)
                    .addLink(linkedSpanContext)
                    .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                    .setAttribute(DURABLE_OPERATION_ID, info.id())
                    .setAttribute(DURABLE_OPERATION_TYPE, info.type());
            addWorkflowLink(spanBuilder);

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

        var span = spanBuilder.startSpan();
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

        var outcome = info.succeeded() ? "SUCCEEDED" : "FAILED";
        span.setAttribute(DURABLE_ATTEMPT_OUTCOME, outcome);

        if (!info.succeeded() && info.error() != null) {
            span.setStatus(StatusCode.ERROR, info.error().getMessage());
            span.recordException(info.error());
        } else if (info.succeeded()) {
            span.setStatus(StatusCode.OK);
        }

        if (info.endTimestamp() != null) {
            span.end(info.endTimestamp());
        } else {
            span.end();
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

    /** Adds a link to the Workflow span, if one exists, for execution-level correlation. */
    private void addWorkflowLink(SpanBuilder spanBuilder) {
        var currentWorkflowSpan = workflowSpan;
        if (currentWorkflowSpan != null) {
            spanBuilder.addLink(currentWorkflowSpan.getSpanContext());
        }
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

    private static ExtractedContext extractCurrentSpanContext() {
        var spanContext = Span.current().getSpanContext();
        if (!spanContext.isValid()) {
            return null;
        }
        return new ExtractedContext(spanContext.getTraceId(), spanContext.getSpanId());
    }

    private static TracerProvider getDefaultTracerProvider() {
        validateAutoConfigurationCustomizerProviderInstalled();

        var globalTracerProvider = GlobalOpenTelemetry.getTracerProvider();
        if (globalTracerProvider == TracerProvider.noop()) {
            throw new IllegalStateException("InvocationOtelPlugin() requires GlobalOpenTelemetry to be initialized by "
                    + "OtelPluginAutoConfigurationCustomizerProvider through the OpenTelemetry Java agent.");
        }
        logger.info(
                "InvocationOtelPlugin initialized from existing GlobalOpenTelemetry tracer provider {}; assuming "
                        + "deterministic span IDs were installed through AutoConfigurationCustomizerProvider",
                globalTracerProvider.getClass().getName());
        return globalTracerProvider;
    }

    private static DeterministicIdGenerator createDefaultIdGenerator() {
        // This is intentionally a separate instance from the SPI provider's generator. The Java agent extension and
        // application may load this plugin in different class loaders, so DeterministicIdGenerator bridges invocation
        // state through system properties that the SPI-installed generator can read when spans are started.
        return new DeterministicIdGenerator();
    }

    private static void validateAutoConfigurationCustomizerProviderInstalled() {
        if (OtelPluginAutoConfigurationState.isInstalled()) {
            return;
        }
        throw new IllegalStateException(
                "InvocationOtelPlugin() requires OtelPluginAutoConfigurationCustomizerProvider to be installed by the "
                        + "OpenTelemetry Java agent. Package this plugin jar as an agent extension and set "
                        + "OTEL_JAVAAGENT_EXTENSIONS or -Dotel.javaagent.extensions to that jar before constructing "
                        + "InvocationOtelPlugin(). "
                        + javaAgentExtensionsDiagnostic());
    }

    private static String javaAgentExtensionsDiagnostic() {
        var propertyValue = System.getProperty("otel.javaagent.extensions");
        var environmentValue = System.getenv("OTEL_JAVAAGENT_EXTENSIONS");
        var configuredPath = propertyValue != null ? propertyValue : environmentValue;
        return "otel.javaagent.extensions="
                + valueOrUnset(propertyValue)
                + ", OTEL_JAVAAGENT_EXTENSIONS="
                + valueOrUnset(environmentValue)
                + ", configured extension path exists="
                + extensionPathExists(configuredPath);
    }

    private static String valueOrUnset(String value) {
        return value != null ? value : "<unset>";
    }

    private static boolean extensionPathExists(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return false;
        }
        var firstPath = configuredPath.split(",", 2)[0];
        return Files.exists(Path.of(firstPath));
    }

    private static SdkTracerProvider getSdkTracerProviderForFlush(TracerProvider tracerProvider) {
        if (tracerProvider instanceof SdkTracerProvider sdkTracerProvider) {
            return sdkTracerProvider;
        }
        logger.info(
                "InvocationOtelPlugin forceFlush is not available because GlobalOpenTelemetry provider {} is not an "
                        + "SdkTracerProvider visible to the application class loader; spans will rely on the "
                        + "provider's own flushing.",
                tracerProvider.getClass().getName());
        return null;
    }
}
