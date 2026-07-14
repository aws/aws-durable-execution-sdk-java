// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.ServiceAttributes;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
 *   <li>Lambda Layer: {@code AWSOpenTelemetryDistroJava} (provides the OTLP collector extension)
 *   <li>Tracing: Active (to populate {@code _X_AMZN_TRACE_ID})
 * </ul>
 *
 * <p>When using {@link #OtelPlugin()}, the plugin uses the global provider directly when one exists and assumes the
 * deterministic ID generator was installed by {@link OtelPluginAutoConfigurationCustomizerProvider}. Otherwise it
 * creates a default OTLP exporter from the application classpath.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for span/scope storage since the SDK runs user code on multiple
 * threads.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public class OtelPlugin implements DurableExecutionPlugin {

    private static final Logger logger = LoggerFactory.getLogger(OtelPlugin.class);
    private static final String INSTRUMENTATION_NAME = "aws-durable-execution-sdk-java";

    private final SdkTracerProvider sdkTracerProvider;
    private final Tracer tracer;
    private final DeterministicIdGenerator idGenerator;
    private final ContextExtractor contextExtractor;
    private final boolean enableMdc;

    // Per-invocation state
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
     * <p>For ADOT layer usage, configure with an OTLP exporter:
     *
     * <pre>{@code
     * var otlpExporter = OtlpGrpcSpanExporter.getDefault(); // sends to localhost:4317
     * var plugin = new OtelPlugin(
     *     SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(otlpExporter)));
     * }</pre>
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     */
    public OtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder) {
        this(tracerProviderBuilder, new XRayContextExtractor(), true);
    }

    /**
     * Creates an OTel plugin with default settings: X-Ray context extraction, MDC enabled, and default OTLP export.
     *
     * <p>If {@code GlobalOpenTelemetry} is configured, the plugin uses that provider directly and assumes deterministic
     * ID generation was installed by {@link OtelPluginAutoConfigurationCustomizerProvider}. Otherwise, the plugin
     * creates {@code OtlpGrpcSpanExporter.getDefault()} from the application classpath.
     */
    public OtelPlugin() {
        this(getDefaultTracerProvider());
    }

    /**
     * Creates an OTel plugin with a custom context extractor, MDC enabled.
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     * @param contextExtractor extracts parent trace context from the Lambda environment
     */
    public OtelPlugin(SdkTracerProviderBuilder tracerProviderBuilder, ContextExtractor contextExtractor) {
        this(tracerProviderBuilder, contextExtractor, true);
    }

    /**
     * Creates an OTel plugin with full configuration.
     *
     * @param tracerProviderBuilder the tracer provider builder (ID generator will be overridden)
     * @param contextExtractor extracts parent trace context from the Lambda environment
     * @param enableMdc if true, injects traceId/spanId/traceSampled into SLF4J MDC for log correlation
     */
    public OtelPlugin(
            SdkTracerProviderBuilder tracerProviderBuilder, ContextExtractor contextExtractor, boolean enableMdc) {
        this(
                DefaultTracerProvider.fromSdkBuilder(tracerProviderBuilder, new DeterministicIdGenerator()),
                contextExtractor,
                enableMdc);
    }

    private OtelPlugin(DefaultTracerProvider defaultTracerProvider) {
        this(defaultTracerProvider, new XRayContextExtractor(), true);
    }

    private OtelPlugin(
            DefaultTracerProvider defaultTracerProvider, ContextExtractor contextExtractor, boolean enableMdc) {
        this.idGenerator = defaultTracerProvider.idGenerator();

        if (defaultTracerProvider.sdkBuilder() != null) {
            // Set service.name to "invocation" — X-Ray uses this as the display name for SERVER spans,
            // creating a separate service node in the trace map labeled "invocation".
            var resource = Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, "invocation"));
            defaultTracerProvider.sdkBuilder().addResource(resource);

            var sdkBuilder = defaultTracerProvider.sdkBuilder();
            if (defaultTracerProvider.installIdGenerator()) {
                sdkBuilder.setIdGenerator(idGenerator);
            }

            this.sdkTracerProvider = sdkBuilder.build();
            this.tracer = sdkTracerProvider.get(INSTRUMENTATION_NAME);
        } else {
            this.sdkTracerProvider = defaultTracerProvider.sdkTracerProvider();
            this.tracer = defaultTracerProvider.tracerProvider().get(INSTRUMENTATION_NAME);
        }

        this.contextExtractor = contextExtractor;
        this.enableMdc = enableMdc;
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

        // Determine parent context for the invocation span.
        Context parentContext;
        if (extractedContext != null && extractedContext.parentSpanId() != null) {
            // X-Ray header has parent — create the invocation span as a child of that segment.
            // This connects our OTLP-exported spans to the Lambda service's X-Ray segments.
            var parentSpanContext = SpanContext.createFromRemoteParent(
                    extractedContext.traceId(),
                    extractedContext.parentSpanId(),
                    TraceFlags.getSampled(),
                    TraceState.getDefault());
            parentContext = Context.root().with(Span.wrap(parentSpanContext));
        } else {
            parentContext = Context.root();
        }

        // Create a SERVER span to establish a separate X-Ray service node.
        // X-Ray uses service.name for the segment display name.
        var spanBuilder = tracer.spanBuilder("invocation")
                .setSpanKind(SpanKind.SERVER)
                .setParent(parentContext)
                .setAttribute(DURABLE_EXECUTION_ARN, info.durableExecutionArn())
                .setAttribute(DURABLE_FIRST_INVOCATION, info.isFirstInvocation());

        if (info.requestId() != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey("faas.invocation_id"), info.requestId());
        }

        invocationSpan = spanBuilder.startSpan();
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        if (invocationSpan == null) return;

        // End any operation spans that are still open (operations that didn't complete in this invocation)
        for (var entry : operationSpans.entrySet()) {
            var span = entry.getValue();
            span.setAttribute(DURABLE_OPERATION_STATUS, "PENDING");
            span.end();
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

        if (info.invocationStatus() == InvocationStatus.FAILED && info.executionError() != null) {
            invocationSpan.setStatus(StatusCode.ERROR, info.executionError().getMessage());
            invocationSpan.recordException(info.executionError());
        }

        invocationSpan.end();
        invocationSpan = null;

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

            if (info.name() != null) {
                spanBuilder.setAttribute(DURABLE_OPERATION_NAME, info.name());
            }

            var continuationSpan = spanBuilder.startSpan();

            if (info.status() != null) {
                continuationSpan.setAttribute(DURABLE_OPERATION_STATUS, info.status());
            }
            if (info.error() != null) {
                continuationSpan.setStatus(StatusCode.ERROR, info.error().getMessage());
                continuationSpan.recordException(info.error());
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

        // Clear MDC after user function completes
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

    private static DefaultTracerProvider getDefaultTracerProvider() {
        validateAutoConfigurationCustomizerProviderInstalled();

        var idGenerator = new DeterministicIdGenerator();
        var globalTracerProvider = GlobalOpenTelemetry.getTracerProvider();
        try {
            var sdkTracerProvider = getGlobalSdkTracerProvider(globalTracerProvider);
            logger.info("OtelPlugin initialized from existing GlobalOpenTelemetry SDK tracer provider; assuming "
                    + "deterministic span IDs were installed through AutoConfigurationCustomizerProvider");
            return DefaultTracerProvider.fromTracerProvider(globalTracerProvider, sdkTracerProvider, idGenerator);
        } catch (IllegalStateException e) {
            if (isJavaAgentTracerProvider(globalTracerProvider)) {
                var sdkTracerProvider = getJavaAgentSdkTracerProvider(globalTracerProvider);
                var javaAgentIdGenerator = OtelPluginAutoConfigurationCustomizerProvider.idGenerator();
                logJavaAgentInitialization(globalTracerProvider, sdkTracerProvider, javaAgentIdGenerator, e);
                return DefaultTracerProvider.fromTracerProvider(globalTracerProvider, sdkTracerProvider, idGenerator);
            } else {
                logger.warn(
                        "OtelPlugin could not initialize from GlobalOpenTelemetry; using default OTLP exporter. "
                                + "Cause: {}",
                        e.getMessage(),
                        e);
            }

            var fallbackBuilder = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(createDefaultOtlpExporter()));
            logger.info("OtelPlugin initialized with a new default OTLP tracer provider without overriding its ID "
                    + "generator; ensure an OTLP collector is reachable");
            return DefaultTracerProvider.fromSdkBuilderWithoutIdGenerator(fallbackBuilder, idGenerator);
        }
    }

    private static void validateAutoConfigurationCustomizerProviderInstalled() {
        if (OtelPluginAutoConfigurationCustomizerProvider.isInstalled()) {
            return;
        }
        throw new IllegalStateException(
                "OtelPlugin() requires OtelPluginAutoConfigurationCustomizerProvider to be installed by the "
                        + "OpenTelemetry Java agent. Package this plugin jar as an agent extension and set "
                        + "OTEL_JAVAAGENT_EXTENSIONS to that jar before constructing OtelPlugin().");
    }

    private static SpanExporter createDefaultOtlpExporter() {
        try {
            var exporterClass = Class.forName("io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter");
            var getDefault = exporterClass.getMethod("getDefault");
            return asSpanExporter(getDefault);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "OtelPlugin() requires either GlobalOpenTelemetry backed by the OpenTelemetry SDK or "
                            + "opentelemetry-exporter-otlp on the application classpath.",
                    e);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to create default OTLP exporter", e);
        }
    }

    private static SpanExporter asSpanExporter(Method getDefault) {
        try {
            var exporter = getDefault.invoke(null);
            if (exporter instanceof SpanExporter spanExporter) {
                return spanExporter;
            }
            throw new IllegalStateException("Default OTLP exporter is not an OpenTelemetry SpanExporter");
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to create default OTLP exporter", e);
        }
    }

    private static SdkTracerProvider getGlobalSdkTracerProvider(TracerProvider tracerProvider) {
        if (tracerProvider instanceof SdkTracerProvider sdkTracerProvider) {
            return sdkTracerProvider;
        }
        return unobfuscateSdkTracerProvider(tracerProvider);
    }

    private static boolean isJavaAgentTracerProvider(TracerProvider tracerProvider) {
        return tracerProvider.getClass().getName().startsWith("io.opentelemetry.javaagent.");
    }

    private static SdkTracerProvider getJavaAgentSdkTracerProvider(TracerProvider tracerProvider) {
        Object agentTracerProvider;
        try {
            agentTracerProvider = getField(tracerProvider, "agentTracerProvider", Object.class);
        } catch (IllegalStateException e) {
            logger.warn(
                    "OtelPlugin could not inspect Java agent tracer provider {}; using the global provider directly.",
                    tracerProvider.getClass().getName(),
                    e);
            return null;
        }

        if (agentTracerProvider instanceof SdkTracerProvider sdkTracerProvider) {
            return sdkTracerProvider;
        }
        if (agentTracerProvider instanceof TracerProvider nestedTracerProvider) {
            try {
                return unobfuscateSdkTracerProvider(nestedTracerProvider);
            } catch (IllegalStateException e) {
                logger.warn(
                        "OtelPlugin could not inspect nested Java agent SDK tracer provider {}; using the global "
                                + "provider directly.",
                        nestedTracerProvider.getClass().getName(),
                        e);
                return null;
            }
        }
        logger.info(
                "OtelPlugin could not inspect Java agent SDK tracer provider because field agentTracerProvider has "
                        + "type {}; using the global provider directly.",
                agentTracerProvider != null ? agentTracerProvider.getClass().getName() : "null");
        return null;
    }

    private static void logJavaAgentInitialization(
            TracerProvider globalTracerProvider,
            SdkTracerProvider sdkTracerProvider,
            DeterministicIdGenerator idGenerator,
            IllegalStateException cause) {
        if (sdkTracerProvider == null) {
            logger.info(
                    "OtelPlugin initialized from existing GlobalOpenTelemetry Java agent tracer provider {}, but the "
                            + "agent SDK provider is not visible to the application class loader, so deterministic "
                            + "ID generator installation cannot be verified. If durable operation span IDs are not "
                            + "deterministic, configure this plugin jar with OTEL_JAVAAGENT_EXTENSIONS. Cause: {}",
                    globalTracerProvider.getClass().getName(),
                    cause.getMessage());
            return;
        }
        if (hasIdGenerator(sdkTracerProvider, idGenerator)) {
            logger.info(
                    "OtelPlugin initialized from existing GlobalOpenTelemetry Java agent tracer provider {}; "
                            + "deterministic span IDs installed through AutoConfigurationCustomizerProvider. Cause: {}",
                    globalTracerProvider.getClass().getName(),
                    cause.getMessage());
            return;
        }
        logger.warn(
                "OtelPlugin initialized from existing GlobalOpenTelemetry Java agent tracer provider {}, but the "
                        + "agent SDK provider is not using the durable execution ID generator. Configure this plugin "
                        + "jar with OTEL_JAVAAGENT_EXTENSIONS so AutoConfigurationCustomizerProvider can install "
                        + "deterministic span IDs. Cause: {}",
                globalTracerProvider.getClass().getName(),
                cause.getMessage());
    }

    private static boolean hasIdGenerator(SdkTracerProvider sdkTracerProvider, DeterministicIdGenerator idGenerator) {
        try {
            var sharedState = getField(sdkTracerProvider, "sharedState", Object.class);
            return getField(sharedState, "idGenerator", Object.class) == idGenerator;
        } catch (IllegalStateException e) {
            logger.warn("OtelPlugin could not verify the Java agent SDK ID generator", e);
            return false;
        }
    }

    private static SdkTracerProvider unobfuscateSdkTracerProvider(TracerProvider tracerProvider) {
        var providerClassName = tracerProvider.getClass().getName();
        try {
            var unobfuscate = tracerProvider.getClass().getDeclaredMethod("unobfuscate");
            unobfuscate.setAccessible(true);
            var sdkTracerProvider = unobfuscate.invoke(tracerProvider);
            if (sdkTracerProvider instanceof SdkTracerProvider result) {
                return result;
            }
            throw new IllegalStateException(
                    "GlobalOpenTelemetry provider " + providerClassName + " is not backed by an SdkTracerProvider");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "GlobalOpenTelemetry provider " + providerClassName + " does not expose unobfuscate(); "
                            + "ADOT Java instrumentation may not have initialized an SDK provider.",
                    e);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(
                    "Unable to invoke unobfuscate() on GlobalOpenTelemetry provider " + providerClassName, e);
        }
    }

    private static <T> T getField(Object target, String fieldName, Class<T> fieldType) {
        try {
            var field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return fieldType.cast(field.get(target));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Unable to copy OpenTelemetry SDK field: " + fieldName, e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        var current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private record DefaultTracerProvider(
            SdkTracerProviderBuilder sdkBuilder,
            TracerProvider tracerProvider,
            SdkTracerProvider sdkTracerProvider,
            DeterministicIdGenerator idGenerator,
            boolean installIdGenerator) {

        private static DefaultTracerProvider fromSdkBuilder(
                SdkTracerProviderBuilder sdkBuilder, DeterministicIdGenerator idGenerator) {
            return new DefaultTracerProvider(sdkBuilder, null, null, idGenerator, true);
        }

        private static DefaultTracerProvider fromSdkBuilderWithoutIdGenerator(
                SdkTracerProviderBuilder sdkBuilder, DeterministicIdGenerator idGenerator) {
            return new DefaultTracerProvider(sdkBuilder, null, null, idGenerator, false);
        }

        private static DefaultTracerProvider fromTracerProvider(
                TracerProvider tracerProvider,
                SdkTracerProvider sdkTracerProvider,
                DeterministicIdGenerator idGenerator) {
            return new DefaultTracerProvider(null, tracerProvider, sdkTracerProvider, idGenerator, false);
        }
    }
}
