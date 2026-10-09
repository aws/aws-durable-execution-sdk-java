// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

/**
 * Verifies the durable sampling decision crosses the application/Java-agent class-loader boundary.
 *
 * <p>Under the documented ADOT setup the plugin JAR is loaded twice: the application class loader computes and stores
 * the decision, and a separate Java-agent extension class loader installs and runs the sampler. Because a
 * {@link io.opentelemetry.context.ContextKey} uses reference identity, the two loaders hold distinct keys and the
 * context carrier alone cannot bridge them. This test reproduces that topology with two child-first class loaders that
 * each load {@code DurableSamplingDecision} separately while sharing the OpenTelemetry API/SDK types with the parent,
 * then asserts the thread-scoped system-property bridge carries the decision from one loader to the other.
 */
class DurableSamplingDecisionClassLoaderTest {
    @ParameterizedTest
    @CsvSource({
        "InvocationOtelPlugin,local,false", "InvocationOtelPlugin,local,true",
        "InvocationOtelPlugin,foreign,false", "InvocationOtelPlugin,foreign,true",
        "InvocationOtelPlugin,opaque,false", "InvocationOtelPlugin,opaque,true",
        "ExecutionOtelPlugin,local,false", "ExecutionOtelPlugin,local,true",
        "ExecutionOtelPlugin,foreign,false", "ExecutionOtelPlugin,foreign,true",
        "ExecutionOtelPlugin,opaque,false", "ExecutionOtelPlugin,opaque,true"
    })
    void customSamplerMetadataSurvivesProviderOwnershipBoundaries(
            String pluginName, String topology, boolean sharedTraceExecutions) throws Exception {
        var previousHeader = System.getProperty("com.amazonaws.xray.traceHeader");
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();
        System.setProperty("com.amazonaws.xray.traceHeader", "Root=1-6955b900-123456789012345678901234");
        var evaluations = new AtomicInteger();
        var key = AttributeKey.stringKey("sampler.extra");
        var delegate = new Sampler() {
            public SamplingResult shouldSample(
                    Context parent,
                    String traceId,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                evaluations.incrementAndGet();
                var second =
                        attributes.get(SpanAttributes.DURABLE_EXECUTION_ARN).contains("/second/");
                var metadata = sharedTraceExecutions ? (second ? "second" : "first") : "kept";
                return new SamplingResult() {
                    public SamplingDecision getDecision() {
                        return sharedTraceExecutions && !second
                                ? SamplingDecision.RECORD_ONLY
                                : SamplingDecision.RECORD_AND_SAMPLE;
                    }

                    public Attributes getAttributes() {
                        return Attributes.of(key, metadata);
                    }

                    public TraceState getUpdatedTraceState(TraceState parentState) {
                        return parentState.toBuilder().put("vendor", metadata).build();
                    }
                };
            }

            public String getDescription() {
                return "custom-metadata";
            }
        };
        try (var appLoader = pluginClassLoader();
                var agentLoader = pluginClassLoader();
                var exporter = InMemorySpanExporter.create()) {
            var loader = topology.equals("local") ? appLoader : agentLoader;
            var samplerType = Class.forName(DurableSampler.class.getName(), true, loader);
            var wrap = samplerType.getDeclaredMethod("wrap", Sampler.class);
            wrap.setAccessible(true);
            var idType = Class.forName(DeterministicIdGenerator.class.getName(), true, loader);
            try (var provider = SdkTracerProvider.builder()
                    .setSampler((Sampler) wrap.invoke(null, delegate))
                    .setIdGenerator((IdGenerator) idType.getConstructor().newInstance())
                    .addSpanProcessor(SimpleSpanProcessor.builder(exporter)
                            .setExportUnsampledSpans(true)
                            .build())
                    .build()) {
                var hidden = new TracerProvider() {
                    public Tracer get(String name) {
                        return provider.get(name);
                    }

                    public Tracer get(String name, String version) {
                        return provider.get(name, version);
                    }
                };
                GlobalOpenTelemetry.set(new OpenTelemetry() {
                    public TracerProvider getTracerProvider() {
                        return topology.equals("opaque") ? hidden : provider;
                    }

                    public ContextPropagators getPropagators() {
                        return ContextPropagators.noop();
                    }
                });
                var plugin = (DurableExecutionPlugin)
                        Class.forName("software.amazon.lambda.durable.otel." + pluginName, true, appLoader)
                                .getConstructor()
                                .newInstance();
                var executionCount = sharedTraceExecutions ? 2 : 1;
                for (var index = 0; index < executionCount; index++) {
                    var executionName = index == 0 ? "first" : "second";
                    var arn = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/" + executionName
                            + "/id";
                    for (var first : new boolean[] {true, false}) {
                        plugin.onInvocationStart(new InvocationInfo("request", arn, first, Instant.ofEpochSecond(10)));
                        plugin.onInvocationEnd(
                                new InvocationEndInfo("request", arn, first, InvocationStatus.PENDING, null));
                    }
                }
                var spans = exporter.getFinishedSpanItems().stream()
                        .filter(s -> s.getName().equals("Invocation"))
                        .toList();
                assertEquals(executionCount * 2, spans.size());
                for (var span : spans) {
                    var second = span.getAttributes()
                            .get(SpanAttributes.DURABLE_EXECUTION_ARN)
                            .contains("/second/");
                    var expected = sharedTraceExecutions ? (second ? "second" : "first") : "kept";
                    assertEquals(expected, span.getAttributes().get(key), span.getName());
                    assertEquals(expected, span.getSpanContext().getTraceState().get("vendor"), span.getName());
                    assertEquals(
                            !sharedTraceExecutions || second,
                            span.getSpanContext().isSampled());
                    assertEquals("6955b900123456789012345678901234", span.getTraceId());
                }
                assertEquals(executionCount * (topology.equals("local") ? 2 : 1), evaluations.get());
            }
        } finally {
            GlobalOpenTelemetry.resetForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
            if (previousHeader == null) System.clearProperty("com.amazonaws.xray.traceHeader");
            else System.setProperty("com.amazonaws.xray.traceHeader", previousHeader);
        }
    }

    @AfterEach
    void clearBridge() {
        DurableSamplingDecision.clearSharedStateForTest();
    }

    @Test
    void decisionCrossesClassLoaderBoundary_viaScopedProperty() throws Exception {
        try (var appLoader = pluginClassLoader();
                var agentLoader = pluginClassLoader()) {

            var appDecision = Class.forName(DurableSamplingDecision.class.getName(), true, appLoader);
            var agentDecision = Class.forName(DurableSamplingDecision.class.getName(), true, agentLoader);

            // The two loaders really did load distinct copies of the class.
            assertNotSame(appDecision, agentDecision, "Each class loader must load its own DurableSamplingDecision");

            // Build a resolved Intent from the application-side loader's own Intent type.
            var appIntentClass = Class.forName(DurableSamplingDecision.class.getName() + "$Intent", true, appLoader);
            var resolvedFactory = appIntentClass.getDeclaredMethod("resolved", SamplingResult.class);
            resolvedFactory.setAccessible(true);
            var appIntent = resolvedFactory.invoke(null, SamplingResult.drop());

            var openScope = appDecision.getDeclaredMethod("openScope", appIntentClass);
            openScope.setAccessible(true);
            var get = agentDecision.getDeclaredMethod("get", Context.class);
            get.setAccessible(true);

            // The application-side loader publishes the intent on this thread; the agent-side loader reads it back from
            // a ROOT context (its context key would be a different instance and would miss), reconstructing its own
            // Intent from the bridged value.
            var scope = (AutoCloseable) openScope.invoke(null, appIntent);
            try {
                var crossLoaderIntent = get.invoke(null, Context.root());
                assertNotNull(
                        crossLoaderIntent, "The agent-side loader must read the intent published by the app side");
                // Its Intent type is the agent loader's copy; read the resolved SamplingResult reflectively.
                var resolvedAccessor = crossLoaderIntent.getClass().getMethod("resolved");
                resolvedAccessor.setAccessible(true);
                var resolved = (SamplingResult) resolvedAccessor.invoke(crossLoaderIntent);
                assertEquals(
                        SamplingDecision.DROP,
                        resolved.getDecision(),
                        "The agent-side loader must read the decision published by the application-side loader");
            } finally {
                scope.close();
            }

            // After the scope closes, the bridge is cleared and the agent-side read returns null (delegate applies).
            assertNull(get.invoke(null, Context.root()), "Closing the scope clears the cross-loader decision");
        }
    }

    /**
     * A child-first class loader that loads {@code software.amazon.lambda.durable.otel.*} itself (so each instance
     * holds its own copies, mirroring the two plugin class loaders) while delegating OpenTelemetry and JDK classes to
     * the parent so those types are shared and interoperable across loaders.
     */
    private static URLClassLoader pluginClassLoader() {
        var classesDir = DurableSamplingDecisionClassLoaderTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
        // target/test-classes -> the main classes live in target/classes alongside it.
        URL mainClasses;
        try {
            mainClasses = new URL(classesDir.toString().replace("/test-classes/", "/classes/"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var parent = DurableSamplingDecisionClassLoaderTest.class.getClassLoader();
        return new URLClassLoader(new URL[] {mainClasses}, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("software.amazon.lambda.durable.otel.")) {
                    synchronized (getClassLoadingLock(name)) {
                        var loaded = findLoadedClass(name);
                        if (loaded == null) {
                            loaded = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }
}
