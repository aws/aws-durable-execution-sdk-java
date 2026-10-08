// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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
class SamplingProcessorIsolationTest {

    @AfterEach
    void clearBridge() {
        DurableSamplingDecision.clearSharedStateForTest();
    }

    @ParameterizedTest
    @CsvSource({
        "InvocationOtelPlugin,true,false,Invocation",
        "InvocationOtelPlugin,false,false,Invocation",
        "InvocationOtelPlugin,false,true,Invocation",
        "ExecutionOtelPlugin,true,false,Invocation",
        "ExecutionOtelPlugin,false,false,Invocation",
        "ExecutionOtelPlugin,false,true,Invocation"
    })
    void agentProcessorForwardingParentCannotReuseApplicationSamplingIntent(
            String pluginName, boolean hideProvider, boolean localSampler, String observedSpan) throws Exception {
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
                                    (Sampler) (localSampler ? appWrap : agentWrap).invoke(null, Sampler.alwaysOff()))
                            .setIdGenerator(
                                    (IdGenerator) agentIdType.getConstructor().newInstance())
                            .addSpanProcessor(new SpanProcessor() {
                                @Override
                                public void onStart(Context parent, ReadWriteSpan span) {
                                    if (!span.getName().equals(observedSpan)) return;
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
                assertFalse(ambient.isRecording(), "The app provider drops unrelated spans outside the callback");
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
        var classesDir = SamplingProcessorIsolationTest.class
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
        var parent = SamplingProcessorIsolationTest.class.getClassLoader();
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
