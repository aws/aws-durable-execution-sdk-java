// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
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
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.plugin.OperationEndInfo;

/** Shared utilities for the OTel plugins' ADOT Java agent SPI path. */
final class OtelPluginSupport {

    /** Always attempt provider cleanup, retaining the approved plugin-local failure precedence. */
    static void finishWithRootCleanup(Runnable finish, Runnable cleanup) {
        Throwable primary = null;
        try {
            finish.run();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                cleanup.run();
            } catch (Throwable failure) {
                if (primary == null) throw failure;
                if (primary != failure) {
                    if (endFailurePriority(failure) > endFailurePriority(primary)) {
                        failure.addSuppressed(primary);
                        throw failure;
                    }
                    primary.addSuppressed(failure);
                }
            }
        }
    }

    @SuppressWarnings("removal")
    private static int endFailurePriority(Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) return 3;
        if (failure instanceof Error && !(failure instanceof LinkageError)) return 2;
        return 1;
    }

    private static final Logger logger = LoggerFactory.getLogger(OtelPluginSupport.class);

    private OtelPluginSupport() {}

    /** Keeps finalization on another thread from consuming an operation owner's eventual scope cleanup. */
    static Scope onOwnerThread(Scope scope) {
        var owner = Thread.currentThread();
        return new Scope() {
            // Only the owner can read or mutate this flag; other threads return before accessing it.
            private boolean closed;

            @Override
            public void close() {
                if (Thread.currentThread() != owner || closed) return;
                closed = true;
                scope.close();
            }
        };
    }

    /** Only the same DurableSampler class copy can consume this loader's parent-context holder. */
    static boolean usesLocalDurableSampler(SdkTracerProvider provider) {
        return provider != null && provider.getSampler() instanceof DurableSampler;
    }

    /** A visible replacement sampler cannot consume our bridge; an opaque agent provider may still need it. */
    static boolean usesDurableSamplingBridge(SdkTracerProvider provider) {
        return provider == null || provider.getSampler().getClass().getName().equals(DurableSampler.class.getName());
    }

    /** Terminal backend status and exception details are independent telemetry signals. */
    static void applyOperationStatus(Span span, OperationEndInfo info) {
        applyOperationStatus(span, info, null);
    }

    static void applyOperationStatus(Span span, OperationEndInfo info, Instant endTimestamp) {
        if (hasOperationErrorDetails(info.error())) {
            span.setStatus(StatusCode.ERROR, info.error().getMessage());
            if (endTimestamp == null || endTimestamp.equals(Instant.EPOCH)) {
                // The SDK treats epoch zero as its current-clock sentinel when ending a span.
                span.recordException(info.error(), operationExceptionAttributes(info.error()));
            } else {
                recordExceptionAt(span, info.error(), endTimestamp);
            }
        } else if (info.error() != null
                || "FAILED".equals(info.status())
                || "TIMED_OUT".equals(info.status())
                || "STOPPED".equals(info.status())) {
            span.setStatus(StatusCode.ERROR);
        } else if (info.error() == null && ("SUCCEEDED".equals(info.status()) || info.status() == null)) {
            // Retain successful statusless virtual operations; unrelated cancellation/nonterminal statuses stay UNSET.
            span.setStatus(StatusCode.OK);
        }
    }

    /** Timestamped public exception event; the provider applies its event attribute limits. */
    private static void recordExceptionAt(Span span, Throwable error, Instant timestamp) {
        if (!span.isRecording()) return;
        var attributes = Attributes.builder();
        var type = error.getClass().getCanonicalName();
        if (type != null) attributes.put("exception.type", type);
        var message = error.getMessage();
        if (message != null) attributes.put("exception.message", message);
        var stacktrace = new StringWriter();
        try (var writer = new PrintWriter(stacktrace)) {
            error.printStackTrace(writer);
        }
        attributes.put("exception.stacktrace", stacktrace.toString());
        attributes.putAll(operationExceptionAttributes(error));
        span.addEvent("exception", attributes.build(), timestamp);
    }

    /** Override only the operation event's type; retain the actual throwable's message and stack diagnostics. */
    private static Attributes operationExceptionAttributes(Throwable error) {
        if (error instanceof DurableOperationException failure) {
            var details = failure.getErrorObject();
            if (details != null
                    && details.errorType() != null
                    && !details.errorType().isBlank()) {
                return Attributes.builder()
                        .put("exception.type", details.errorType())
                        .build();
            }
        }
        return Attributes.empty();
    }

    /** Distinguishes operation telemetry details without changing the SDK's failure notification object. */
    static boolean hasOperationErrorDetails(Throwable error) {
        if (error == null) return false;
        if (error instanceof DurableOperationException failure) {
            var details = failure.getErrorObject();
            if (details != null) {
                return details.errorType() != null
                        || details.errorMessage() != null
                        || details.errorData() != null
                        || details.hasStackTrace()
                        || error.getMessage() != null
                        || error.getCause() != null
                        || error.getSuppressed().length > 0
                        || error.getStackTrace().length > 0;
            }
        }
        // Preserve ordinary/custom Throwable behavior, including wrappers without an ErrorObject.
        return true;
    }

    /** Creates a new DeterministicIdGenerator for the application-side state bridge. */
    static DeterministicIdGenerator createDefaultIdGenerator() {
        return new DeterministicIdGenerator();
    }

    /**
     * Resolves the provider's full sampling result once per invocation using the stable propagated parent and canonical
     * trace ID. Ambient spans and synthesized ancestors must not feed a new decision back into a ParentBased policy.
     * Foreign/opaque durable samplers resolve in their own loader, preserving attributes and trace state. A plain
     * replacement retains normal per-span sampling; its fallback root supplies its own result at creation time.
     */
    static SamplingResult resolveSamplingResult(
            SdkTracerProvider sdkTracerProvider,
            ExtractedContext extracted,
            String canonicalTraceId,
            String spanName,
            Attributes attributes) {
        if (usesLocalDurableSampler(sdkTracerProvider)) {
            return sdkTracerProvider
                    .getSampler()
                    .shouldSample(
                            samplingParent(extracted),
                            canonicalTraceId,
                            spanName,
                            SpanKind.INTERNAL,
                            attributes,
                            Collections.emptyList());
        }
        return null;
    }

    /** The original remote parent, never a per-invocation ambient span or a synthesized ancestor. */
    static Context samplingParent(ExtractedContext extracted) {
        if (extracted == null || !extracted.hasCompleteRemoteParent()) return Context.root();
        // X-Ray carries no tracestate. A valid parent with no sampled flag is unsampled, not absent.
        var flags = extracted.sampling() == ExtractedContext.Sampling.SAMPLED
                ? TraceFlags.getSampled()
                : TraceFlags.getDefault();
        return Context.root()
                .with(Span.wrap(SpanContext.createFromRemoteParent(
                        extracted.traceId(), extracted.parentSpanId(), flags, TraceState.getDefault())));
    }

    /** A known dropped ambient span cannot anchor exported durable spans. */
    static boolean usableAmbientParent(Span ambient, String canonicalTraceId) {
        var context = ambient.getSpanContext();
        return context.isValid()
                && context.getTraceId().equals(canonicalTraceId)
                && (context.isSampled() || ambient.isRecording());
    }

    /**
     * Provisional synthetic-ancestor flags. A deferred result is resolved by the actual root span before descendants
     * are created. Neither these flags nor the synthesized ancestor are inputs to the configured sampler.
     */
    static boolean isSampled(SamplingResult samplingResult) {
        return samplingResult == null
                || samplingResult.getDecision()
                        == io.opentelemetry.sdk.trace.samplers.SamplingDecision.RECORD_AND_SAMPLE;
    }

    /** The tracer provider and tracer resolved from the global OpenTelemetry instance. */
    record ProviderSetup(SdkTracerProvider sdkTracerProvider, Tracer tracer) {}

    /**
     * Tries to resolve the ADOT/global tracer provider without installing OpenTelemetry's no-op global. This is called
     * at invocation start so a plugin constructed before the Java agent finishes initialization can bind later.
     *
     * @param instrumentationName the instrumentation scope name
     * @param pluginName the plugin name used in diagnostics/flush logging
     * @return the resolved provider and tracer, or {@code null} when telemetry must be disabled for this invocation
     */
    static ProviderSetup tryResolveGlobalProvider(String instrumentationName, String pluginName) {
        try {
            return resolveGlobalProvider(instrumentationName, pluginName);
        } catch (LinkageError error) {
            logger.warn(
                    "{} telemetry is disabled for this invocation because the visible OpenTelemetry dependencies "
                            + "are incompatible. Align the OpenTelemetry API, SDK, and Java agent versions.",
                    pluginName,
                    error);
            return null;
        }
    }

    private static ProviderSetup resolveGlobalProvider(String instrumentationName, String pluginName) {
        if (!OtelPluginAutoConfigurationState.isInstalled()) {
            logger.warn(
                    "{} telemetry is disabled for this invocation because "
                            + "OtelPluginAutoConfigurationCustomizerProvider is not installed yet. Provider resolution "
                            + "will be retried on the next invocation. {}",
                    pluginName,
                    javaAgentExtensionsDiagnostic());
            return null;
        }
        if (!supportsGlobalProviderLookup(pluginName)) {
            return null;
        }
        if (!GlobalOpenTelemetry.isSet()) {
            logger.warn(
                    "{} telemetry is disabled for this invocation because GlobalOpenTelemetry is not initialized yet. "
                            + "Provider resolution will be retried on the next invocation.",
                    pluginName);
            return null;
        }

        var tracerProvider = GlobalOpenTelemetry.getOrNoop().getTracerProvider();
        if (tracerProvider == TracerProvider.noop()) {
            logger.warn(
                    "{} telemetry is disabled for this invocation because GlobalOpenTelemetry contains a no-op tracer "
                            + "provider. Provider resolution will be retried on the next invocation.",
                    pluginName);
            return null;
        }

        logger.info(
                "{} initialized from existing GlobalOpenTelemetry tracer provider {}; assuming "
                        + "deterministic span IDs were installed through AutoConfigurationCustomizerProvider",
                pluginName,
                tracerProvider.getClass().getName());
        return new ProviderSetup(
                getSdkTracerProviderForFlush(tracerProvider, pluginName), tracerProvider.get(instrumentationName));
    }

    private static boolean supportsGlobalProviderLookup(String pluginName) {
        try {
            GlobalOpenTelemetry.class.getMethod("isSet");
            GlobalOpenTelemetry.class.getMethod("getOrNoop");
            return true;
        } catch (NoSuchMethodException missingApi) {
            logger.warn(
                    "{} telemetry is disabled for this invocation because the visible OpenTelemetry API lacks {}. "
                            + "Global provider binding requires GlobalOpenTelemetry.isSet() and getOrNoop(); "
                            + "align the API, SDK, and Java agent versions.",
                    pluginName,
                    missingApi.getMessage());
            return false;
        }
    }

    /** Returns the SdkTracerProvider for flushing, or null if the provider is wrapped by the agent classloader. */
    static SdkTracerProvider getSdkTracerProviderForFlush(TracerProvider tracerProvider, String pluginName) {
        if (tracerProvider instanceof SdkTracerProvider sdkTracerProvider) {
            return sdkTracerProvider;
        }
        logger.info(
                "{} forceFlush is not available because GlobalOpenTelemetry provider {} is not an "
                        + "SdkTracerProvider visible to the application class loader; spans will rely on the "
                        + "provider's own flushing.",
                pluginName,
                tracerProvider.getClass().getName());
        return null;
    }

    private static String javaAgentExtensionsDiagnostic() {
        var propertyValue = System.getProperty("otel.javaagent.extensions");
        var environmentValue = System.getenv("OTEL_JAVAAGENT_EXTENSIONS");
        var configuredPath = propertyValue != null ? propertyValue : environmentValue;
        return "otel.javaagent.extensions="
                + (propertyValue != null ? propertyValue : "<unset>")
                + ", OTEL_JAVAAGENT_EXTENSIONS="
                + (environmentValue != null ? environmentValue : "<unset>")
                + ", configured extension path exists="
                + extensionPathExists(configuredPath);
    }

    private static boolean extensionPathExists(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return false;
        }
        var firstPath = configuredPath.split(",", 2)[0];
        return Files.exists(Path.of(firstPath));
    }
}
