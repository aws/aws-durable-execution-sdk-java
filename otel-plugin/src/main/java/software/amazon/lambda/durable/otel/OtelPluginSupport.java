// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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

    /**
     * Resolves the durable execution's sampling decision for one invocation as a full {@link SamplingResult}, evaluated
     * exactly once. The decision is applied to every durable span of the invocation (Workflow, Invocation, operation,
     * attempt) via {@link DurableSampler}, so the configured sampler is not re-invoked per span and the three-way
     * decision — including {@code RECORD_ONLY} — is preserved rather than reduced to a boolean.
     *
     * <p>Sampling precedence, highest first:
     *
     * <ol>
     *   <li><b>Explicit upstream {@code Sampled}</b> on the propagated header ({@code Sampled=1} /{@code Sampled=0}).
     *       An authoritative backend decision is preserved regardless of the configured sampler;
     *   <li><b>Same-trace ambient span.</b> When no explicit {@code Sampled} is present but a valid ambient span (an
     *       auto-instrumentation Lambda handler span, {@code Span.current()}) is already on the canonical execution
     *       trace, its established decision is followed. The ambient span is a same-trace descendant of the propagated
     *       parent, so its decision is representative of this trace. Its full three-way decision is preserved: a
     *       sampled span yields {@code RECORD_AND_SAMPLE}; an unsampled but recording span yields {@code RECORD_ONLY}
     *       (its spans still reach processors); only an unsampled, non-recording span yields {@code DROP};
     *   <li><b>Application-owned provider: configured sampler, once.</b> When the tracer provider is reachable (the
     *       two-argument constructor path), its sampler is read directly and evaluated a single time with
     *       {@code ROOT_CONTEXT} (so a parent-based sampler applies its root policy), the canonical trace ID, span
     *       name, and attributes, and its full result is returned;
     *   <li><b>Java-agent path: defer to the installed sampler.</b> When the provider is not visible
     *       ({@code sdkTracerProvider == null}), this returns {@code null} to defer. It does <em>not</em> reconstruct
     *       the sampler from environment settings: the agent's effective sampler is whatever its autoconfiguration
     *       pipeline finally installs, and another extension's customizer can wrap or replace a recognized configured
     *       sampler, so a reconstruction could disagree with the real delegate. Deferring routes the decision to the
     *       agent-installed {@link DurableSampler}, which consults its actual delegate once per execution, caches the
     *       result by trace ID, and reuses it for the execution's remaining durable spans (see
     *       {@link DurableSampler#shouldSample}). The delegate's decision is honored in full — including a
     *       {@code DROP}/rate-limited outcome — so durable spans are not force-sampled.
     * </ol>
     *
     * <p>For the app-path branches, nothing is persisted across invocations: each invocation recomputes the decision
     * from stable inputs (the canonical trace ID and the upstream {@code Sampled} value), so deterministic samplers
     * reach the same decision on every reinvocation. The ambient-span branch is inherently per-invocation because the
     * ambient span is recreated each invocation, which the shared spec accepts for same-trace ambient membership. On
     * the agent path the delegate is consulted once per execution within a process and cached; the delegate itself (for
     * example a ratio sampler) governs cross-invocation consistency.
     *
     * @param sdkTracerProvider the resolved provider, or null when it is not visible to the application (agent path)
     * @param extracted the context parsed from the propagated header, or null when none is present
     * @param ambientSpan the current ambient span ({@code Span.current()}); its context may be invalid and its
     *     recording state distinguishes RECORD_ONLY from DROP for an unsampled same-trace parent
     * @param canonicalTraceId the canonical execution trace ID (used for the ambient same-trace check and the sampler)
     * @param spanName the span name passed to the sampler
     * @param attributes the attributes the span is started with
     * @return the resolved {@link SamplingResult}, or {@code null} when the decision is deferred to the agent-side
     *     {@link DurableSampler}'s own delegate
     */
    static SamplingResult resolveSamplingResult(
            SdkTracerProvider sdkTracerProvider,
            ExtractedContext extracted,
            Span ambientSpan,
            String canonicalTraceId,
            String spanName,
            Attributes attributes) {
        // 1. An explicit upstream decision is authoritative.
        if (extracted != null) {
            switch (extracted.sampling()) {
                case SAMPLED:
                    return SamplingResult.recordAndSample();
                case NOT_SAMPLED:
                    return SamplingResult.drop();
                case UNDECIDED:
                    break;
            }
        }
        // 2. No explicit decision: follow a valid same-trace ambient span's established decision. The sampled bit alone
        // cannot distinguish RECORD_ONLY (recording, unsampled) from DROP (not recording, unsampled), so a sampled bit
        // maps to RECORD_AND_SAMPLE, an unsampled-but-recording span maps to RECORD_ONLY (its spans still reach
        // processors), and only an unsampled, non-recording span maps to DROP.
        var ambient = ambientSpan != null ? ambientSpan.getSpanContext() : null;
        if (ambient != null && ambient.isValid() && ambient.getTraceId().equals(canonicalTraceId)) {
            if (ambient.getTraceFlags().isSampled()) {
                return SamplingResult.recordAndSample();
            }
            return ambientSpan.isRecording() ? SamplingResult.recordOnly() : SamplingResult.drop();
        }
        // 3. An application-owned provider exposes the real sampler: evaluate it once, preserving its full result.
        if (sdkTracerProvider != null) {
            return sdkTracerProvider
                    .getSampler()
                    .shouldSample(
                            Context.root(),
                            canonicalTraceId,
                            spanName,
                            SpanKind.INTERNAL,
                            attributes,
                            Collections.emptyList());
        }
        // 4. Agent path (provider not visible from the application class loader): defer. The agent's effective sampler
        // is whatever the autoconfiguration pipeline finally installs — a recognized configured sampler can be wrapped
        // or replaced by another extension's customizer, so it cannot be reliably reconstructed from environment
        // settings here. Return null so the agent-installed DurableSampler consults its actual delegate once per
        // execution and caches the result, honoring the customer's effective policy (including drop/rate-limit).
        return null;
    }

    /**
     * True when the decision records and samples, used to derive the (never-exported) execution-ancestor trace flags. A
     * {@code null} decision is unresolved (deferred to the agent-side sampler); it defaults the ancestor flag to
     * sampled so a parent-based delegate is not biased toward dropping, while the agent-side {@link DurableSampler}
     * still makes the authoritative per-span decision from its real delegate.
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
