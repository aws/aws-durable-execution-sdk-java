// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import java.util.Map;

/**
 * Immutable configuration for {@link InvocationOtelPlugin} and {@link ExecutionOtelPlugin}.
 *
 * <p>Replaces the previous telescoping constructor overloads with a single named-field builder, giving readable,
 * type-safe call sites and forward compatibility (new options are added as builder methods, not new constructors). This
 * mirrors the {@code OtelPluginConfig} object in the JavaScript SDK and the {@code OtelPluginConfig} dataclass in the
 * Python SDK for cross-SDK parity.
 *
 * <p>Construct via {@link #builder()} and pass to a plugin's {@code (SdkTracerProviderBuilder, OtelPluginConfig)}
 * constructor:
 *
 * <pre>{@code
 * var config = OtelPluginConfig.builder()
 *     .contextExtractor(new XRayContextExtractor())
 *     .enableMdc(true)
 *     .workflowSpanName("Workflow")
 *     .instrumentationName("my-scope")
 *     .build();
 * var plugin = new InvocationOtelPlugin(tracerProviderBuilder, config);
 * }</pre>
 *
 * <p>Defaults: {@code contextExtractor = new XRayContextExtractor()}, {@code enableMdc = true}, {@code workflowSpanName
 * = "Workflow"}, {@code instrumentationName = "aws-durable-execution-sdk-java"}. A {@code null} passed to any builder
 * setter falls back to the corresponding default.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class OtelPluginConfig {

    static final String DEFAULT_INSTRUMENTATION_NAME = "aws-durable-execution-sdk-java";
    static final String DEFAULT_WORKFLOW_SPAN_NAME = "Workflow";

    private final ContextExtractor contextExtractor;
    private final boolean enableMdc;
    private final String workflowSpanName;
    private final String instrumentationName;
    private final boolean useDefaultTracerProvider;
    private final String otlpEndpoint;
    private final Map<String, String> otlpHeaders;

    private OtelPluginConfig(Builder builder) {
        this.contextExtractor =
                builder.contextExtractor != null ? builder.contextExtractor : new XRayContextExtractor();
        this.enableMdc = builder.enableMdc;
        this.workflowSpanName =
                builder.workflowSpanName != null ? builder.workflowSpanName : DEFAULT_WORKFLOW_SPAN_NAME;
        this.instrumentationName =
                builder.instrumentationName != null ? builder.instrumentationName : DEFAULT_INSTRUMENTATION_NAME;
        this.useDefaultTracerProvider = builder.useDefaultTracerProvider;
        this.otlpEndpoint = builder.otlpEndpoint;
        this.otlpHeaders = builder.otlpHeaders != null ? Map.copyOf(builder.otlpHeaders) : Map.of();
    }

    /** Returns a new builder with all fields defaulted. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a config with all default values. */
    public static OtelPluginConfig defaults() {
        return new Builder().build();
    }

    /** The context extractor used to read parent trace context from the Lambda environment. */
    public ContextExtractor contextExtractor() {
        return contextExtractor;
    }

    /** Whether traceId/spanId/otelTraceSampled are injected into the SLF4J MDC for log correlation. */
    public boolean enableMdc() {
        return enableMdc;
    }

    /** The name used for the Workflow span. */
    public String workflowSpanName() {
        return workflowSpanName;
    }

    /** The instrumentation scope name registered with the tracer. */
    public String instrumentationName() {
        return instrumentationName;
    }

    /**
     * Whether to use the globally configured (ADOT) provider instead of an auto-configured OTLP provider. Only
     * consulted when no {@code SdkTracerProviderBuilder} was supplied. Defaults to {@code false}.
     */
    public boolean useDefaultTracerProvider() {
        return useDefaultTracerProvider;
    }

    /** OTLP/HTTP endpoint for the auto-configured provider, or {@code null} to use the OTel default / env var. */
    public String otlpEndpoint() {
        return otlpEndpoint;
    }

    /** Extra headers sent by the auto-configured OTLP exporter (never {@code null}). */
    public Map<String, String> otlpHeaders() {
        return otlpHeaders;
    }

    /**
     * The provider source selected by this config when no {@code SdkTracerProviderBuilder} is supplied.
     *
     * <p>Returns {@link ProviderSource#GLOBAL} when {@link #useDefaultTracerProvider()} is true, otherwise
     * {@link ProviderSource#AUTO_OTLP}. (A supplied builder is always {@link ProviderSource#EXPLICIT}, decided by the
     * constructor rather than the config.)
     */
    public ProviderSource resolveSource() {
        return useDefaultTracerProvider ? ProviderSource.GLOBAL : ProviderSource.AUTO_OTLP;
    }

    /**
     * Builder for {@link OtelPluginConfig}.
     *
     * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
     */
    @Deprecated
    public static final class Builder {

        private ContextExtractor contextExtractor;
        private boolean enableMdc = true;
        private String workflowSpanName;
        private String instrumentationName;
        private boolean useDefaultTracerProvider = false;
        private String otlpEndpoint;
        private Map<String, String> otlpHeaders;

        private Builder() {}

        /**
         * Sets the context extractor. Defaults to {@link XRayContextExtractor} when null.
         *
         * @param contextExtractor extracts parent trace context from the Lambda environment
         * @return this builder
         */
        public Builder contextExtractor(ContextExtractor contextExtractor) {
            this.contextExtractor = contextExtractor;
            return this;
        }

        /**
         * Sets whether to inject traceId/spanId/otelTraceSampled into the SLF4J MDC. Defaults to {@code true}.
         *
         * @param enableMdc if true, enables MDC log correlation
         * @return this builder
         */
        public Builder enableMdc(boolean enableMdc) {
            this.enableMdc = enableMdc;
            return this;
        }

        /**
         * Sets the Workflow span name. Defaults to {@code "Workflow"} when null.
         *
         * @param workflowSpanName the name for the Workflow span
         * @return this builder
         */
        public Builder workflowSpanName(String workflowSpanName) {
            this.workflowSpanName = workflowSpanName;
            return this;
        }

        /**
         * Sets the instrumentation scope name registered with the tracer. Defaults to
         * {@code "aws-durable-execution-sdk-java"} when null.
         *
         * @param instrumentationName the instrumentation scope name
         * @return this builder
         */
        public Builder instrumentationName(String instrumentationName) {
            this.instrumentationName = instrumentationName;
            return this;
        }

        /**
         * Sets whether to use the globally configured (ADOT) provider instead of an auto-configured OTLP provider. Only
         * consulted when no {@code SdkTracerProviderBuilder} is supplied. Defaults to {@code false} (auto-OTLP).
         *
         * @param useDefaultTracerProvider if true, resolve to {@link ProviderSource#GLOBAL}
         * @return this builder
         */
        public Builder useDefaultTracerProvider(boolean useDefaultTracerProvider) {
            this.useDefaultTracerProvider = useDefaultTracerProvider;
            return this;
        }

        /**
         * Sets the OTLP/HTTP endpoint for the auto-configured provider. When null, the OTel default (or
         * {@code OTEL_EXPORTER_OTLP_ENDPOINT}) is used.
         *
         * @param otlpEndpoint the OTLP/HTTP traces endpoint
         * @return this builder
         */
        public Builder otlpEndpoint(String otlpEndpoint) {
            this.otlpEndpoint = otlpEndpoint;
            return this;
        }

        /**
         * Sets extra headers for the auto-configured OTLP exporter (e.g. auth headers for a third-party endpoint).
         *
         * @param otlpHeaders header name/value pairs; null is treated as empty
         * @return this builder
         */
        public Builder otlpHeaders(Map<String, String> otlpHeaders) {
            this.otlpHeaders = otlpHeaders;
            return this;
        }

        /** Builds an immutable {@link OtelPluginConfig}. */
        public OtelPluginConfig build() {
            return new OtelPluginConfig(this);
        }
    }
}
