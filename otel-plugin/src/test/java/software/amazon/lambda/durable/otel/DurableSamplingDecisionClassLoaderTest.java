// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
