// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared utilities for OTel plugin default constructor support (ADOT Java agent SPI path). */
final class OtelPluginSupport {

    private static final Logger logger = LoggerFactory.getLogger(OtelPluginSupport.class);

    private OtelPluginSupport() {}

    /** Creates a new DeterministicIdGenerator for the application-side state bridge. */
    static DeterministicIdGenerator createDefaultIdGenerator() {
        return new DeterministicIdGenerator();
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
        if (!OtelPluginAutoConfigurationState.isInstalled()) {
            logger.warn(
                    "{} telemetry is disabled for this invocation because "
                            + "OtelPluginAutoConfigurationCustomizerProvider is not installed yet. Provider resolution "
                            + "will be retried on the next invocation. {}",
                    pluginName,
                    javaAgentExtensionsDiagnostic());
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

    /** Extracts trace context from the current OTel span (fallback when X-Ray header is unavailable). */
    static ExtractedContext extractCurrentSpanContext() {
        var spanContext = Span.current().getSpanContext();
        if (!spanContext.isValid()) {
            return null;
        }
        return new ExtractedContext(spanContext.getTraceId(), spanContext.getSpanId());
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
