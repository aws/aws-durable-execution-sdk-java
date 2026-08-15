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

    /** Gets the global TracerProvider after validating the SPI was installed. */
    static TracerProvider getDefaultTracerProvider(String pluginName) {
        validateAutoConfigurationCustomizerProviderInstalled(pluginName);

        var globalTracerProvider = GlobalOpenTelemetry.getTracerProvider();
        if (globalTracerProvider == TracerProvider.noop()) {
            throw new IllegalStateException(pluginName + "() requires GlobalOpenTelemetry to be initialized by "
                    + "OtelPluginAutoConfigurationCustomizerProvider through the OpenTelemetry Java agent.");
        }
        logger.info(
                "{} initialized from existing GlobalOpenTelemetry tracer provider {}; assuming "
                        + "deterministic span IDs were installed through AutoConfigurationCustomizerProvider",
                pluginName,
                globalTracerProvider.getClass().getName());
        return globalTracerProvider;
    }

    /** Creates a new DeterministicIdGenerator for the application-side state bridge. */
    static DeterministicIdGenerator createDefaultIdGenerator() {
        return new DeterministicIdGenerator();
    }

    /**
     * The tracer provider, tracer, and ID generator resolved for a config-only plugin constructor, plus the
     * {@link ProviderSource} that produced them.
     */
    record ProviderSetup(
            ProviderSource source,
            SdkTracerProvider sdkTracerProvider,
            Tracer tracer,
            DeterministicIdGenerator idGenerator) {}

    /**
     * Resolves the tracer provider for the config-only plugin constructors from
     * {@link OtelPluginConfig#providerSource()}, centralizing the {@link ProviderSource} branching shared by
     * {@link InvocationOtelPlugin} and {@link ExecutionOtelPlugin}:
     *
     * <ul>
     *   <li>{@link ProviderSource#GLOBAL} — binds to the ADOT/global provider (not plugin-owned); the deterministic ID
     *       generator is created for the application-side state bridge.
     *   <li>{@link ProviderSource#EXPLICIT} — rejected: an explicit provider requires the
     *       {@code (SdkTracerProviderBuilder, OtelPluginConfig)} constructor.
     * </ul>
     *
     * @param config the plugin configuration
     * @param pluginName the plugin name used in diagnostics/flush logging
     * @return the resolved provider, tracer, ID generator, and source
     * @throws IllegalArgumentException if {@code config.providerSource()} is {@link ProviderSource#EXPLICIT}
     */
    static ProviderSetup resolveConfiguredProvider(OtelPluginConfig config, String pluginName) {
        return switch (config.providerSource()) {
            case GLOBAL -> {
                var idGenerator = createDefaultIdGenerator();
                var tracerProvider = getDefaultTracerProvider(pluginName);
                yield new ProviderSetup(
                        ProviderSource.GLOBAL,
                        getSdkTracerProviderForFlush(tracerProvider, pluginName),
                        tracerProvider.get(config.instrumentationName()),
                        idGenerator);
            }
            case EXPLICIT ->
                throw new IllegalArgumentException(
                        "OtelPluginConfig.providerSource(EXPLICIT) requires a caller-supplied SdkTracerProviderBuilder; "
                                + "use the (SdkTracerProviderBuilder, OtelPluginConfig) constructor.");
        };
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

    private static void validateAutoConfigurationCustomizerProviderInstalled(String pluginName) {
        if (OtelPluginAutoConfigurationState.isInstalled()) {
            return;
        }
        throw new IllegalStateException(
                pluginName + "() requires OtelPluginAutoConfigurationCustomizerProvider to be installed by the "
                        + "OpenTelemetry Java agent. Package this plugin jar as an agent extension and set "
                        + "OTEL_JAVAAGENT_EXTENSIONS or -Dotel.javaagent.extensions to that jar before constructing "
                        + pluginName + "(). "
                        + javaAgentExtensionsDiagnostic());
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
