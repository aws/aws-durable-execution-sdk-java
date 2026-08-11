// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.ServiceAttributes;
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
     * Builds a plugin-owned {@link SdkTracerProvider} that exports over OTLP/HTTP (the {@link ProviderSource#AUTO_OTLP}
     * default). Mirrors the auto-configured provider in the JavaScript and Python SDK plugins: an OTLP/HTTP exporter, a
     * batch span processor, an env-driven sampler, Lambda resource attributes, and the deterministic ID generator.
     *
     * @param config the plugin configuration (endpoint + headers)
     * @param idGenerator the deterministic ID generator to install
     * @param additionalResource extra resource attributes to merge (e.g. ExecutionOtelPlugin's service.name), or null
     */
    static SdkTracerProvider buildAutoOtlpProvider(
            OtelPluginConfig config, DeterministicIdGenerator idGenerator, Resource additionalResource) {
        var exporterBuilder = OtlpHttpSpanExporter.builder();
        var endpoint = resolveOtlpEndpoint(config);
        if (endpoint != null) {
            exporterBuilder.setEndpoint(endpoint);
        }
        for (var header : config.otlpHeaders().entrySet()) {
            exporterBuilder.addHeader(header.getKey(), header.getValue());
        }

        var resource = buildLambdaResource();
        if (additionalResource != null) {
            resource = resource.merge(additionalResource);
        }

        return SdkTracerProvider.builder()
                .setIdGenerator(idGenerator)
                .setSampler(resolveSampler())
                .setResource(resource)
                .addSpanProcessor(
                        BatchSpanProcessor.builder(exporterBuilder.build()).build())
                .build();
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
     *   <li>{@link ProviderSource#AUTO_OTLP} — builds a plugin-owned OTLP/HTTP provider (see
     *       {@link #buildAutoOtlpProvider}).
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
            case AUTO_OTLP -> {
                var idGenerator = new DeterministicIdGenerator();
                var sdkTracerProvider = buildAutoOtlpProvider(config, idGenerator, null);
                yield new ProviderSetup(
                        ProviderSource.AUTO_OTLP,
                        sdkTracerProvider,
                        sdkTracerProvider.get(config.instrumentationName()),
                        idGenerator);
            }
            case EXPLICIT ->
                throw new IllegalArgumentException(
                        "OtelPluginConfig.providerSource(EXPLICIT) requires a caller-supplied SdkTracerProviderBuilder; "
                                + "use the (SdkTracerProviderBuilder, OtelPluginConfig) constructor.");
        };
    }

    /** Resolves the OTLP/HTTP traces endpoint (config -> env -> exporter default), appending the signal path. */
    private static String resolveOtlpEndpoint(OtelPluginConfig config) {
        if (config.otlpEndpoint() != null && !config.otlpEndpoint().isBlank()) {
            return config.otlpEndpoint();
        }
        var envEndpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (envEndpoint != null && !envEndpoint.isBlank()) {
            var base = envEndpoint.endsWith("/") ? envEndpoint.substring(0, envEndpoint.length() - 1) : envEndpoint;
            return base.endsWith("/v1/traces") ? base : base + "/v1/traces";
        }
        // null -> the OTLP/HTTP exporter's own default (http://localhost:4318/v1/traces)
        return null;
    }

    /** Builds the sampler from {@code OTEL_DURABLE_SAMPLING_RATIO}, falling back to always-on. */
    private static Sampler resolveSampler() {
        var raw = System.getenv("OTEL_DURABLE_SAMPLING_RATIO");
        if (raw != null) {
            try {
                var ratio = Double.parseDouble(raw);
                if (ratio >= 0.0 && ratio <= 1.0) {
                    return Sampler.traceIdRatioBased(ratio);
                }
            } catch (NumberFormatException ignored) {
                // fall through to always-on
            }
        }
        return Sampler.alwaysOn();
    }

    /** Builds Lambda resource attributes from AWS_* env vars, merged onto the default resource. */
    private static Resource buildLambdaResource() {
        var functionName = System.getenv("AWS_LAMBDA_FUNCTION_NAME");
        if (functionName == null || functionName.isBlank()) {
            return Resource.getDefault();
        }
        var attributes = Attributes.builder()
                .put(ServiceAttributes.SERVICE_NAME, functionName)
                .put("faas.name", functionName)
                .put("cloud.provider", "aws")
                .put("cloud.platform", "aws_lambda");
        var region = System.getenv("AWS_REGION");
        if (region != null && !region.isBlank()) {
            attributes.put("cloud.region", region);
        }
        var version = System.getenv("AWS_LAMBDA_FUNCTION_VERSION");
        if (version != null && !version.isBlank()) {
            attributes.put("faas.version", version);
        }
        return Resource.getDefault().merge(Resource.create(attributes.build()));
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
