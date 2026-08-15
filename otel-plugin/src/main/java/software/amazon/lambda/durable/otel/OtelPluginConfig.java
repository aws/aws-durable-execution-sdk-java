// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

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
 * = "Workflow"}, {@code instrumentationName = "aws-durable-execution-sdk-java"}, {@code providerSource =
 * ProviderSource.GLOBAL}. A {@code null} passed to any builder setter falls back to the corresponding default.
 */
public final class OtelPluginConfig {

    static final String DEFAULT_INSTRUMENTATION_NAME = "aws-durable-execution-sdk-java";
    static final String DEFAULT_WORKFLOW_SPAN_NAME = "Workflow";

    private final ContextExtractor contextExtractor;
    private final boolean enableMdc;
    private final String workflowSpanName;
    private final String instrumentationName;
    private final ProviderSource providerSource;

    private OtelPluginConfig(Builder builder) {
        this.contextExtractor =
                builder.contextExtractor != null ? builder.contextExtractor : new XRayContextExtractor();
        this.enableMdc = builder.enableMdc;
        this.workflowSpanName =
                builder.workflowSpanName != null ? builder.workflowSpanName : DEFAULT_WORKFLOW_SPAN_NAME;
        this.instrumentationName =
                builder.instrumentationName != null ? builder.instrumentationName : DEFAULT_INSTRUMENTATION_NAME;
        this.providerSource = builder.providerSource != null ? builder.providerSource : ProviderSource.GLOBAL;
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
     * The tracer-provider source to use when no {@code SdkTracerProviderBuilder} is supplied (the config-only
     * constructors). {@link ProviderSource#GLOBAL} (the default) uses the globally configured ADOT provider.
     *
     * <p>{@link ProviderSource#EXPLICIT} is not valid here — it is implied by using a {@code (SdkTracerProviderBuilder,
     * OtelPluginConfig)} constructor and is rejected by the config-only constructors.
     */
    public ProviderSource providerSource() {
        return providerSource;
    }

    /** Builder for {@link OtelPluginConfig}. */
    public static final class Builder {

        private ContextExtractor contextExtractor;
        private boolean enableMdc = true;
        private String workflowSpanName;
        private String instrumentationName;
        private ProviderSource providerSource = ProviderSource.GLOBAL;

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
         * Sets the tracer-provider source used when no {@code SdkTracerProviderBuilder} is supplied. The config-only
         * constructors accept {@link ProviderSource#GLOBAL}; a {@code null} also falls back to
         * {@link ProviderSource#GLOBAL}.
         *
         * <p>{@link ProviderSource#EXPLICIT} is not accepted through the config-only constructors — supply a
         * {@code SdkTracerProviderBuilder} via the two-arg constructor instead.
         *
         * @param providerSource the provider source
         * @return this builder
         */
        public Builder providerSource(ProviderSource providerSource) {
            this.providerSource = providerSource != null ? providerSource : ProviderSource.GLOBAL;
            return this;
        }

        /** Builds an immutable {@link OtelPluginConfig}. */
        public OtelPluginConfig build() {
            return new OtelPluginConfig(this);
        }
    }
}
