// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

/**
 * Which resolution path produced a plugin's tracer provider.
 *
 * <p>Mirrors the {@code ProviderSource} used by the JavaScript and Python SDK OTel plugins for cross-SDK parity:
 *
 * <ul>
 *   <li>{@link #EXPLICIT} — the caller supplied a {@link io.opentelemetry.sdk.trace.SdkTracerProviderBuilder} (the
 *       {@code (SdkTracerProviderBuilder, OtelPluginConfig)} constructors); the plugin builds and owns that provider.
 *   <li>{@link #GLOBAL} — {@code GlobalOpenTelemetry} / the ADOT Java agent (the no-arg or config-only constructors);
 *       the plugin does not own the provider.
 * </ul>
 *
 * <p>This is the single knob that selects a plugin's tracer provider. {@link OtelPluginConfig#providerSource()} carries
 * it for the config-only constructors; the {@code (SdkTracerProviderBuilder, OtelPluginConfig)} constructors always
 * report {@link #EXPLICIT}.
 */
public enum ProviderSource {
    /** Caller-supplied {@code SdkTracerProviderBuilder}; plugin-owned. */
    EXPLICIT,
    /** Globally configured provider (ADOT Java agent); not plugin-owned. */
    GLOBAL
}
