// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

/**
 * Which of the three resolution tiers produced a plugin's tracer provider.
 *
 * <p>Mirrors the {@code ProviderSource} used by the JavaScript and Python SDK OTel plugins for cross-SDK parity:
 *
 * <ul>
 *   <li>{@link #EXPLICIT} — the caller supplied a {@link io.opentelemetry.sdk.trace.SdkTracerProviderBuilder} (the
 *       {@code (SdkTracerProviderBuilder, OtelPluginConfig)} constructors); the plugin builds and owns that provider.
 *   <li>{@link #GLOBAL} — {@code GlobalOpenTelemetry} / the ADOT Java agent (the no-arg constructor, or a config with
 *       {@code useDefaultTracerProvider(true)}); the plugin does not own the provider.
 *   <li>{@link #AUTO_OTLP} — the default when only an {@link OtelPluginConfig} is supplied and
 *       {@code useDefaultTracerProvider} is false: the plugin builds and owns an OTLP/HTTP provider.
 * </ul>
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public enum ProviderSource {
    /** Caller-supplied {@code SdkTracerProviderBuilder}; plugin-owned. */
    EXPLICIT,
    /** Globally configured provider (ADOT Java agent); not plugin-owned. */
    GLOBAL,
    /** Auto-configured OTLP/HTTP provider; plugin-owned. */
    AUTO_OTLP;

    /** True when the plugin created (and therefore manages/flushes) the provider. */
    public boolean ownsProvider() {
        return this == EXPLICIT || this == AUTO_OTLP;
    }
}
