// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

/**
 * Everything the OTel plugins need that belongs to the execution environment rather than to one invocation.
 *
 * <p>A plugin instance now serves exactly one Lambda invocation, so the objects that must exist once per environment
 * live here: the resolved {@link OtelPluginConfig}, the {@link DeterministicIdGenerator}, and — for an
 * application-owned tracer provider — the built provider and its tracer. {@code InvocationOtelPlugin.factory(...)} and
 * {@code ExecutionOtelPlugin.factory(...)} create one of these and hand the same instance to every plugin instance they
 * create, so the provider is built (and its ID generator and sampler installed) once per environment rather than once
 * per invocation.
 *
 * <p>On the ADOT Java agent path there is no provider to build here: the global provider is resolved on first use and
 * then cached. An invocation that runs before the agent has finished initializing therefore disables telemetry for
 * itself only, and the next invocation's instance resolves the provider again.
 */
final class OtelPluginEnvironment {

    private final OtelPluginConfig config;
    private final DeterministicIdGenerator idGenerator;

    /** The application-owned provider and tracer, or null on the Java agent path. */
    private final OtelPluginSupport.ProviderSetup ownedSetup;

    /**
     * The global provider and tracer, once resolved. Environment-lifetime state shared by every invocation's instance,
     * hence volatile; a lost race only resolves the same global provider twice.
     */
    private volatile OtelPluginSupport.ProviderSetup resolvedGlobalSetup;

    private OtelPluginEnvironment(
            OtelPluginConfig config, DeterministicIdGenerator idGenerator, OtelPluginSupport.ProviderSetup ownedSetup) {
        this.config = config;
        this.idGenerator = idGenerator;
        this.ownedSetup = ownedSetup;
    }

    /**
     * Builds the application-owned provider once: wraps the builder's ID generator and sampler, builds the provider and
     * gets the tracer. Every invocation's plugin instance then shares them.
     */
    static OtelPluginEnvironment forProviderBuilder(
            SdkTracerProviderBuilder tracerProviderBuilder, OtelPluginConfig config) {
        var idGenerator = DeterministicIdGenerator.installOn(tracerProviderBuilder);
        // Wrap the configured sampler so durable spans use the execution's single precomputed decision.
        DurableSampler.installOn(tracerProviderBuilder);
        var sdkTracerProvider = tracerProviderBuilder.build();
        var setup = new OtelPluginSupport.ProviderSetup(
                sdkTracerProvider, sdkTracerProvider.get(config.instrumentationName()));
        return new OtelPluginEnvironment(config, idGenerator, setup);
    }

    /** The Java agent path: the global provider is resolved lazily, when an invocation's instance first needs it. */
    static OtelPluginEnvironment forGlobalProvider(OtelPluginConfig config) {
        return new OtelPluginEnvironment(config, OtelPluginSupport.createDefaultIdGenerator(), null);
    }

    OtelPluginConfig config() {
        return config;
    }

    DeterministicIdGenerator idGenerator() {
        return idGenerator;
    }

    /**
     * The provider and tracer one invocation's plugin instance should use, or {@code null} when telemetry must be
     * disabled for that invocation because the agent's global provider is not available yet.
     *
     * @param pluginName the plugin name used in diagnostics
     */
    OtelPluginSupport.ProviderSetup bind(String pluginName) {
        if (ownedSetup != null) {
            return ownedSetup;
        }
        var alreadyResolved = resolvedGlobalSetup;
        if (alreadyResolved != null) {
            return alreadyResolved;
        }
        var setup = OtelPluginSupport.tryResolveGlobalProvider(config.instrumentationName(), pluginName);
        if (setup != null) {
            // Resolution succeeded, so it holds for the rest of this environment's life: cache it so later invocations
            // neither re-resolve nor re-log it. A failure is not cached — that is what makes the retry per invocation.
            resolvedGlobalSetup = setup;
        }
        return setup;
    }
}
