// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static java.util.Objects.requireNonNull;

import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;

/**
 * Selects the OpenTelemetry view to register with a durable handler. Execution mode is the default; invocation mode
 * places durable operations under their Lambda invocation instead of the Workflow span.
 *
 * <pre>{@code
 * DurableConfig.builder().withPlugins(OtelPlugin.factory()).build();
 * DurableConfig.builder().withPlugins(OtelPlugin.factory(OtelPlugin.Mode.INVOCATION)).build();
 * }</pre>
 *
 * <p>Each call returns a new factory of the selected view's type, carrying that view's configuration and
 * exclusive-group metadata; the returned factory then creates a fresh plugin for every invocation. Calls are not
 * memoized, so registering two of them puts two factories in one exclusive group and
 * {@code DurableConfig.builder().build()} rejects the configuration. The existing execution and invocation factory
 * APIs remain supported. Default configuration uses the global provider configured by ADOT or the application;
 * exporters and provider setup remain the application's responsibility.
 *
 * <p>{@link #factory()} covers the zero-configuration default. Every call that passes configuration or a provider
 * builder names its {@link Mode}, so the selected view is explicit wherever it is not the bare default.
 */
public final class OtelPlugin {
    private OtelPlugin() {}

    /** The hierarchy used for durable operation spans. */
    public enum Mode {
        /** Durable operations are children of the Workflow span. */
        EXECUTION,
        /** Durable operations are children of their Invocation span. */
        INVOCATION
    }

    /** @return an execution-view factory using the global provider and default configuration */
    public static DurableExecutionPluginFactory factory() {
        return factory(Mode.EXECUTION);
    }

    /**
     * @param mode the view to select; must not be null
     * @return a factory using the global provider and default configuration
     */
    public static DurableExecutionPluginFactory factory(Mode mode) {
        return factory(mode, OtelPluginConfig.defaults());
    }

    /**
     * @param mode the view to select; must not be null
     * @param config configuration passed to the selected factory
     * @return a factory using the global provider
     */
    public static DurableExecutionPluginFactory factory(Mode mode, OtelPluginConfig config) {
        return switch (requireNonNull(mode, "mode must not be null")) {
            case EXECUTION -> ExecutionOtelPlugin.factory(config);
            case INVOCATION -> InvocationOtelPlugin.factory(config);
        };
    }

    /**
     * @param mode the view to select; must not be null
     * @param tracerProviderBuilder application-owned provider builder
     * @return a factory using default configuration
     */
    public static DurableExecutionPluginFactory factory(Mode mode, SdkTracerProviderBuilder tracerProviderBuilder) {
        return factory(mode, tracerProviderBuilder, OtelPluginConfig.defaults());
    }

    /**
     * @param mode the view to select; must not be null
     * @param tracerProviderBuilder application-owned provider builder
     * @param config configuration passed to the selected factory
     * @return the selected factory, retaining its registration metadata and per-invocation lifecycle
     */
    public static DurableExecutionPluginFactory factory(
            Mode mode, SdkTracerProviderBuilder tracerProviderBuilder, OtelPluginConfig config) {
        return switch (requireNonNull(mode, "mode must not be null")) {
            case EXECUTION -> ExecutionOtelPlugin.factory(tracerProviderBuilder, config);
            case INVOCATION -> InvocationOtelPlugin.factory(tracerProviderBuilder, config);
        };
    }
}
