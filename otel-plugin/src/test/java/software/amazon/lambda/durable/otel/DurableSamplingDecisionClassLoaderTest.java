// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void materializedRootSuppliesParentSamplingMetadata(String pluginName, String topology, boolean recordOnly)
            throws Exception {
        var previousHeader = System.getProperty("com.amazonaws.xray.traceHeader");
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();
        System.setProperty("com.amazonaws.xray.traceHeader", "Root=1-6955b900-123456789012345678901234");
        var evaluations = new AtomicInteger();
        var key = AttributeKey.stringKey("sampler.extra");
        var result = new SamplingResult() {
            public SamplingDecision getDecision() {
                return recordOnly ? SamplingDecision.RECORD_ONLY : SamplingDecision.RECORD_AND_SAMPLE;
            }

            public Attributes getAttributes() {
                return Attributes.of(key, "kept");
            }

            public TraceState getUpdatedTraceState(TraceState parent) {
                var depth = parent.get("depth");
                return parent.toBuilder()
                        .put("vendor", "kept")
                        .put("depth", Integer.toString(depth == null ? 1 : Integer.parseInt(depth) + 1))
                        .build();
            }
        };
        var delegate = new Sampler() {
            public SamplingResult shouldSample(
                    Context parent,
                    String traceId,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                evaluations.incrementAndGet();
                return result;
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
                var arn = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/custom/id";
                for (var first : new boolean[] {true, false}) {
                    plugin.onInvocationStart(new InvocationInfo("request", arn, first, Instant.ofEpochSecond(10)));
                    plugin.onInvocationEnd(new InvocationEndInfo(
                            "request",
                            arn,
                            first,
                            first ? InvocationStatus.PENDING : InvocationStatus.SUCCEEDED,
                            null));
                }
                var spans = exporter.getFinishedSpanItems();
                var roots = spans.stream()
                        .filter(span -> span.getName().equals("DurableExecutionRoot"))
                        .toList();
                assertEquals(2, roots.size());
                var rootContext = roots.get(0).getSpanContext();
                for (var anchor : roots) {
                    assertEquals(
                            rootContext, anchor.getSpanContext(), "Re-export keeps the complete stable root context");
                    assertEquals(10_000_000_000L, anchor.getStartEpochNanos());
                    assertEquals(anchor.getStartEpochNanos(), anchor.getEndEpochNanos());
                    assertEquals("1", anchor.getSpanContext().getTraceState().get("depth"));
                    assertEquals(!recordOnly, anchor.getSpanContext().isSampled());
                    assertEquals("kept", anchor.getAttributes().get(key));
                }
                var descendants = spans.stream()
                        .filter(span -> span.getName().equals("Invocation")
                                || span.getName().equals("Workflow"))
                        .toList();
                assertEquals(3, descendants.size());
                for (var child : descendants) {
                    assertEquals(
                            rootContext,
                            child.getParentSpanContext(),
                            "A child must record the materialized parent's flags and trace state: " + child.getName());
                    assertEquals("2", child.getSpanContext().getTraceState().get("depth"));
                    assertEquals("kept", child.getAttributes().get(key));
                }
                assertEquals(topology.equals("local") ? 2 : 1, evaluations.get());
            }
        } finally {
            GlobalOpenTelemetry.resetForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
            if (previousHeader == null) System.clearProperty("com.amazonaws.xray.traceHeader");
            else System.setProperty("com.amazonaws.xray.traceHeader", previousHeader);
        }
    }

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
                        .filter(s ->
                                s.getName().equals("Invocation") || s.getName().equals("DurableExecutionRoot"))
                        .toList();
                assertEquals(executionCount * 4, spans.size());
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
            var consume = agentDecision.getDeclaredMethod("consume", Context.class);
            consume.setAccessible(true);

            // The application-side loader publishes the intent on this thread; the agent-side loader reads it back from
            // a ROOT context (its context key would be a different instance and would miss), reconstructing its own
            // Intent from the bridged value.
            var scope = (AutoCloseable) openScope.invoke(null, appIntent);
            try {
                var crossLoaderIntent = consume.invoke(null, Context.root());
                assertNull(get.invoke(null, Context.root()), "The bridge is consumed before onStart processors run");
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

    @ParameterizedTest
    @CsvSource({
        "InvocationOtelPlugin,true,false,DurableExecutionRoot",
        "InvocationOtelPlugin,false,false,DurableExecutionRoot",
        "InvocationOtelPlugin,false,true,DurableExecutionRoot",
        "ExecutionOtelPlugin,true,false,DurableExecutionRoot",
        "ExecutionOtelPlugin,false,false,DurableExecutionRoot",
        "ExecutionOtelPlugin,false,true,DurableExecutionRoot",
        "InvocationOtelPlugin,true,false,Invocation",
        "InvocationOtelPlugin,false,false,Invocation",
        "InvocationOtelPlugin,false,true,Invocation",
        "ExecutionOtelPlugin,true,false,Invocation",
        "ExecutionOtelPlugin,false,false,Invocation",
        "ExecutionOtelPlugin,false,true,Invocation",
        "InvocationOtelPlugin,false,false,PlainInvocation",
        "ExecutionOtelPlugin,false,false,PlainInvocation",
        "InvocationOtelPlugin,false,false,PlainDurableExecutionRoot",
        "ExecutionOtelPlugin,false,false,PlainDurableExecutionRoot"
    })
    void agentProcessorForwardingParentCannotReuseApplicationSamplingIntent(
            String pluginName, boolean hideProvider, boolean localSampler, String observedSpan) throws Exception {
        var plainSampler = observedSpan.startsWith("Plain");
        var spanName = plainSampler ? observedSpan.substring("Plain".length()) : observedSpan;
        var previousHeader = System.getProperty("com.amazonaws.xray.traceHeader");
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();
        System.setProperty("com.amazonaws.xray.traceHeader", "Root=1-6955b900-123456789012345678901234;Sampled=1");
        try (var appLoader = pluginClassLoader();
                var agentLoader = pluginClassLoader()) {
            var appSamplerType = Class.forName(DurableSampler.class.getName(), true, appLoader);
            var appWrap = appSamplerType.getDeclaredMethod("wrap", Sampler.class);
            appWrap.setAccessible(true);
            var agentSamplerType = Class.forName(DurableSampler.class.getName(), true, agentLoader);
            var agentWrap = agentSamplerType.getDeclaredMethod("wrap", Sampler.class);
            agentWrap.setAccessible(true);
            var appDecision = Class.forName(DurableSamplingDecision.class.getName(), true, appLoader);
            var appGet = appDecision.getDeclaredMethod("get", Context.class);
            appGet.setAccessible(true);
            var agentDecision = Class.forName(DurableSamplingDecision.class.getName(), true, agentLoader);
            var agentGet = agentDecision.getDeclaredMethod("get", Context.class);
            agentGet.setAccessible(true);
            var agentIdType = Class.forName(DeterministicIdGenerator.class.getName(), true, agentLoader);
            var callbacks = new AtomicInteger();
            var leakedSampling = new AtomicBoolean();
            try (var appProvider = SdkTracerProvider.builder()
                            .setSampler((Sampler) appWrap.invoke(null, Sampler.alwaysOff()))
                            .build();
                    var agentProvider = SdkTracerProvider.builder()
                            .setSampler(
                                    plainSampler
                                            ? Sampler.alwaysOn()
                                            : (Sampler) (localSampler ? appWrap : agentWrap)
                                                    .invoke(null, Sampler.alwaysOff()))
                            .setIdGenerator(
                                    (IdGenerator) agentIdType.getConstructor().newInstance())
                            .addSpanProcessor(new SpanProcessor() {
                                @Override
                                public void onStart(Context parent, ReadWriteSpan span) {
                                    if (!span.getName().equals(spanName)) return;
                                    var unrelated = appProvider
                                            .get("processor")
                                            .spanBuilder("unrelated-forwarded-parent")
                                            .setParent(parent)
                                            .startSpan();
                                    callbacks.incrementAndGet();
                                    leakedSampling.compareAndSet(false, unrelated.isRecording());
                                    unrelated.end();
                                }

                                @Override
                                public boolean isStartRequired() {
                                    return true;
                                }

                                @Override
                                public void onEnd(ReadableSpan span) {}

                                @Override
                                public boolean isEndRequired() {
                                    return false;
                                }
                            })
                            .build()) {
                assertEquals(localSampler, agentProvider.getSampler().getClass() == appSamplerType);
                var hiddenAgentProvider = new TracerProvider() {
                    @Override
                    public Tracer get(String name) {
                        return agentProvider.get(name);
                    }

                    @Override
                    public Tracer get(String name, String version) {
                        return agentProvider.get(name, version);
                    }
                };
                GlobalOpenTelemetry.set(new OpenTelemetry() {
                    @Override
                    public TracerProvider getTracerProvider() {
                        return hideProvider ? hiddenAgentProvider : agentProvider;
                    }

                    @Override
                    public ContextPropagators getPropagators() {
                        return ContextPropagators.noop();
                    }
                });
                var plugin = (DurableExecutionPlugin)
                        Class.forName("software.amazon.lambda.durable.otel." + pluginName, true, appLoader)
                                .getConstructor()
                                .newInstance();
                var arn = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id";
                var previousAmbient = Span.current();
                var ambient = appProvider
                        .get("ambient")
                        .spanBuilder("ambient-control")
                        .startSpan();
                try (var ambientScope = ambient.makeCurrent()) {
                    for (var first : new boolean[] {true, false}) {
                        plugin.onInvocationStart(new InvocationInfo("request", arn, first, Instant.EPOCH));
                        plugin.onInvocationEnd(
                                new InvocationEndInfo("request", arn, first, InvocationStatus.PENDING, null));
                        assertSame(ambient, Span.current(), "Plugin cleanup must preserve the caller's ambient span");
                        assertNull(appGet.invoke(null, Context.root()), "Application sampling intent must be cleared");
                        assertNull(agentGet.invoke(null, Context.root()), "Agent sampling intent must be cleared");
                    }
                } finally {
                    ambient.end();
                }
                assertSame(previousAmbient, Span.current());
                assertEquals(2, callbacks.get(), "The selected span in both invocations must reach the real processor");
                assertFalse(
                        leakedSampling.get(), "The supplied parent must not override the app provider's DROP policy");
            }
        } finally {
            GlobalOpenTelemetry.resetForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
            if (previousHeader == null) System.clearProperty("com.amazonaws.xray.traceHeader");
            else System.setProperty("com.amazonaws.xray.traceHeader", previousHeader);
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
