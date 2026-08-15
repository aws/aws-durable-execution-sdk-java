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
 *       trace because it uses {@code setNoParent()} with deterministic trace and span IDs. It serves as a correlation
 *       anchor across invocations. The Invocation span and its children nest under the ADOT agent's Lambda segment as
 *       subsegments.
 *   <li>When using a custom {@link io.opentelemetry.sdk.trace.SdkTracerProviderBuilder} (no ADOT agent), the Workflow
 *       span is similarly unparented. Invocation roots receive provider-generated trace IDs, and operation and attempt
 *       spans link to the Workflow span for execution-level correlation.
 * </ul>
 *
 * <p>The Workflow trace ID is derived from the execution start time and ARN, and is independent of the ambient
 * Lambda/X-Ray trace. Invocation spans inherit the active ambient context, or extracted upstream context as a fallback.
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
 * view to inspect parent-child relationships within the ambient Invocation trace and the links to the independent
 * Workflow trace.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for span/scope storage since the SDK runs user code on multiple
 * threads.
 */
public class InvocationOtelPlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(InvocationOtelPlugin.class);

    private final SdkTracerProvider sdkTracerProvider;
    private final Tracer tracer;
    private final DeterministicIdGenerator idGenerator;
    private final ContextExtractor contextExtractor;
    private final boolean enableMdc;
    private final String workflowSpanName;
    private final ProviderSource providerSource;

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
     * <p>Uses {@code GlobalOpenTelemetry} directly and assumes deterministic ID generation was installed by
     * {@code OtelPluginAutoConfigurationCustomizerProvider}.
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

        this.sdkTracerProvider = tracerProviderBuilder.build();
        this.tracer = sdkTracerProvider.get(config.instrumentationName());
        this.contextExtractor = config.contextExtractor();
        this.enableMdc = config.enableMdc();
        this.workflowSpanName = config.workflowSpanName();
        this.providerSource = ProviderSource.EXPLICIT;
    }

    /**
     * Creates an OTel plugin from configuration alone (no caller-supplied tracer provider builder).
     *
     * <p>The config-only constructor uses the ADOT/global provider. {@link ProviderSource#EXPLICIT} is rejected here;
     * supply a {@code SdkTracerProviderBuilder} via the two-arg constructor for an application-owned provider.
     *
     * @param config the plugin configuration
     * @throws IllegalArgumentException if {@code config.providerSource()} is {@link ProviderSource#EXPLICIT}
     */
    public InvocationOtelPlugin(OtelPluginConfig config) {
        this.contextExtractor = config.contextExtractor();
        this.enableMdc = config.enableMdc();
        this.workflowSpanName = config.workflowSpanName();

        var setup = OtelPluginSupport.resolveConfiguredProvider(config, "InvocationOtelPlugin");
        this.providerSource = setup.source();
        this.idGenerator = setup.idGenerator();
        this.sdkTracerProvider = setup.sdkTracerProvider();
        this.tracer = setup.tracer();
    }

    /** The tier that produced this plugin's tracer provider. */
    public ProviderSource providerSource() {
        return providerSource;
    }

    // ─── Invocation hooks ────────────────────────────────────────────────

    @Override
    public void onInvocationStart(InvocationInfo info) {
        this.durableExecutionArn = info.durableExecutionArn();

        // Prefer the active Java-agent span, then fall back to explicitly extracted upstream context.
        var invocationParent = extractCurrentSpanContext();
        if (invocationParent == null) {
            invocationParent = contextExtractor.extract();
        }

        // Workflow root span — one logical span per durable execution, created unconditionally (independent of the
        // X-Ray parent below). Deterministic span ID from the ARN so it is the same across invocations; exported once,
        // on the terminal invocation. Operation and attempt spans link to it for execution-level correlation while
        // remaining parented to the per-invocation span (this plugin stays invocation-rooted).
        var workflowSpanBuilder = tracer.spanBuilder(workflowSpanName)
                .setSpanKind(SpanKind.INTERNAL)
                .setNoParent()
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setStartTimestamp(info.executionStartTime() != null ? info.executionStartTime() : Instant.now());
        var workflowTraceId =
                idGenerator.generateTraceIdForExecution(info.durableExecutionArn(), info.executionStartTime());
        var workflowSpanId = idGenerator.generateWorkflowSpanId(info.durableExecutionArn());
        workflowSpan = idGenerator.startSpan(workflowSpanBuilder, workflowTraceId, workflowSpanId);

        // Determine parent context for the invocation span.
        Context parentContext;
        if (invocationParent != null && invocationParent.parentSpanId() != null) {
            // Reconstruct a remote parent from the extracted trace context (X-Ray header or current span).
            // This connects plugin spans to the Lambda service's X-Ray segments.
            var parentSpanContext = SpanContext.createFromRemoteParent(
                    invocationParent.traceId(),
                    invocationParent.parentSpanId(),
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
            MDC.put(
                    MdcSpanEnricher.MDC_TRACE_ID,
                    invocationSpan.getSpanContext().getTraceId());
        }
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
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
                .setAttribute(DURABLE_OPERATION_TYPE, info.type())
                .setAttribute(DURABLE_OPERATION_STATUS, info.status() != null ? info.status() : "STARTED");

        // Link to the Workflow span for execution-level correlation (operation stays parented to the invocation span).
        addWorkflowLink(spanBuilder);

        if (info.name() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
        }
        if (info.subType() != null) {
            spanBuilder.setAttribute(DURABLE_OPERATION_SUBTYPE, info.subType());
        }

        var span = info.isReplay()
                ? spanBuilder.startSpan()
                : idGenerator.startSpan(
                        spanBuilder, null, idGenerator.generateSpanIdForOperation(durableExecutionArn, info.id()));

        // Store the open span — will be ended in onOperationEnd or onInvocationEnd
        operationSpans.put(info.id(), span);
        operationContexts.put(info.id(), span.getSpanContext());
        operationStartOrder.addLast(info.id());
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
            var parentContext = resolveParentContext(info.parentId());

            var spanBuilder = tracer.spanBuilder(spanName(info.type(), info.subType(), info.name()))
                    .setParent(parentContext)
                    .setAttribute(DURABLE_EXECUTION_ARN, durableExecutionArn)
                    .setAttribute(DURABLE_OPERATION_ID, info.id())
                    .setAttribute(DURABLE_OPERATION_TYPE, info.type());
            addWorkflowLink(spanBuilder);

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

    private Context resolveParentContext(String parentId) {
        if (parentId != null) {
            var parentSpanContext = operationContexts.get(parentId);
            if (parentSpanContext != null) {
                return Context.current().with(Span.wrap(parentSpanContext));
            }
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
        return OtelPluginSupport.extractCurrentSpanContext();
    }
}
