// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

class DynamicPluginLoaderTest {

    @Test
    void unsetConfigurationPreservesExplicitFactoriesWithoutDiscoveringProviders() {
        DurableExecutionPluginFactory explicitFactory = info -> new FirstPlugin();
        Iterable<DurableExecutionPluginProvider> providers = () -> {
            throw new AssertionError("Providers should not be discovered");
        };

        var factories = DynamicPluginLoader.loadConfiguredPluginFactories(null, providers, List.of(explicitFactory));

        assertEquals(1, factories.size());
        assertSame(explicitFactory, factories.get(0));
    }

    @Test
    void loadsRequestedProvidersBeforeExplicitFactoriesInConfiguredOrder() {
        DurableExecutionPluginFactory explicitFactory = info -> new ExplicitPlugin();
        var firstProvider = provider("first", FirstPlugin::new);
        var secondProvider = provider("second", SecondPlugin::new);

        var factories = DynamicPluginLoader.loadConfiguredPluginFactories(
                " second, first ", List.of(firstProvider, secondProvider), List.of(explicitFactory));

        assertSame(secondProvider, factories.get(0));
        assertSame(firstProvider, factories.get(1));
        assertSame(explicitFactory, factories.get(2));
    }

    @Test
    void doesNotCreatePluginsAtConfigurationTime() {
        var creations = new AtomicInteger();
        var requestedProvider = provider("requested", () -> {
            creations.incrementAndGet();
            return new FirstPlugin();
        });

        var factories =
                DynamicPluginLoader.loadConfiguredPluginFactories("requested", List.of(requestedProvider), List.of());

        // Plugins are created per invocation, not while configuration is resolved.
        assertEquals(1, factories.size());
        assertEquals(0, creations.get());
        assertInstanceOf(FirstPlugin.class, factories.get(0).createPlugin(invocationInfo()));
        assertEquals(1, creations.get());
    }

    @Test
    void doesNotSelectProvidersOutsideTheAllowList() {
        var requestedProvider = provider("requested", FirstPlugin::new);
        var unrequestedProvider = provider("unrequested", SecondPlugin::new);

        var factories = DynamicPluginLoader.loadConfiguredPluginFactories(
                "requested", List.of(requestedProvider, unrequestedProvider), List.of());

        assertEquals(List.of(requestedProvider), factories);
    }

    @Test
    void loadsExplicitAndDynamicFactoriesOfTheSameType() {
        DurableExecutionPluginFactory explicitFactory = info -> new FirstPlugin();
        var duplicateProvider = provider("first", FirstPlugin::new);

        var factories = DynamicPluginLoader.loadConfiguredPluginFactories(
                "first", List.of(duplicateProvider), List.of(explicitFactory));

        assertEquals(2, factories.size());
        assertSame(duplicateProvider, factories.get(0));
        assertSame(explicitFactory, factories.get(1));
    }

    @Test
    void rejectsEmptyConfiguredProviderName() {
        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories("first,,second", List.of(), List.of()));

        assertTrue(error.getMessage().contains("must be non-empty"));
        assertTrue(error.getMessage().contains(DynamicPluginLoader.PLUGINS_ENVIRONMENT_VARIABLE));
    }

    @Test
    void rejectsDuplicateConfiguredProviderName() {
        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories("first,first", List.of(), List.of()));

        assertTrue(error.getMessage().contains("listed more than once"));
    }

    @Test
    void rejectsUnknownProviderAndListsAvailableNames() {
        var availableProvider = provider("available", FirstPlugin::new);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories(
                        "missing", List.of(availableProvider), List.of()));

        assertTrue(error.getMessage().contains("No DurableExecutionPluginProvider named 'missing'"));
        assertTrue(error.getMessage().contains("available"));
    }

    @Test
    void rejectsDuplicateDiscoveredProviderNames() {
        var firstProvider = provider("duplicate", FirstPlugin::new);
        var secondProvider = provider("duplicate", SecondPlugin::new);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories(
                        "duplicate", List.of(firstProvider, secondProvider), List.of()));

        assertTrue(error.getMessage().contains("Multiple DurableExecutionPluginProvider implementations"));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void rejectsProviderWithInvalidName() {
        var blankNameProvider = provider(" ", FirstPlugin::new);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories(
                        "first", List.of(blankNameProvider), List.of()));

        assertTrue(error.getMessage().contains("returned an invalid name"));
    }

    @Test
    void wrapsProviderDiscoveryFailure() {
        Iterable<DurableExecutionPluginProvider> providers = () -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                throw new LinkageError("incompatible");
            }

            @Override
            public DurableExecutionPluginProvider next() {
                throw new AssertionError("next should not be called");
            }
        };

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories("first", providers, List.of()));

        assertTrue(error.getMessage().contains("Failed to discover"));
        assertInstanceOf(LinkageError.class, error.getCause());
    }

    // ─── Providers that do implement createPlugin(InvocationInfo) ────────
    //
    // The startup check that rejects a provider compiled against the older provider interface reads whether
    // createPlugin(InvocationInfo) resolves to an abstract method on the runtime class. These cases cover the shapes
    // in which a provider written against this SDK supplies that method without declaring it on its own class, so the
    // check must accept all of them. DynamicPluginLoaderStaleProviderTest covers the case the check rejects.

    @Test
    void acceptsProviderThatDeclaresCreatePluginItself() {
        var declaringProvider = provider("declaring", FirstPlugin::new);

        var factories =
                DynamicPluginLoader.loadConfiguredPluginFactories("declaring", List.of(declaringProvider), List.of());

        assertInstanceOf(FirstPlugin.class, factories.get(0).createPlugin(invocationInfo()));
    }

    @Test
    void acceptsProviderThatInheritsCreatePluginFromAbstractBaseClass() {
        var inheritingProvider = new InheritsFromBaseProvider();

        var factories = DynamicPluginLoader.loadConfiguredPluginFactories(
                "inherits-from-base", List.of(inheritingProvider), List.of());

        assertInstanceOf(FirstPlugin.class, factories.get(0).createPlugin(invocationInfo()));
    }

    @Test
    void acceptsProviderThatInheritsCreatePluginAsDefaultMethod() {
        var inheritingProvider = new InheritsDefaultMethodProvider();

        var factories = DynamicPluginLoader.loadConfiguredPluginFactories(
                "inherits-default-method", List.of(inheritingProvider), List.of());

        assertInstanceOf(FirstPlugin.class, factories.get(0).createPlugin(invocationInfo()));
    }

    @Test
    void acceptsProviderThatNarrowsTheCreatePluginReturnType() {
        // A narrowed return type makes the compiler emit a bridge method, so createPlugin(InvocationInfo) resolves to
        // one of two declarations on the provider class. Neither is abstract.
        var covariantProvider = new NarrowedReturnTypeProvider();

        var factories = DynamicPluginLoader.loadConfiguredPluginFactories(
                "narrowed-return-type", List.of(covariantProvider), List.of());

        assertInstanceOf(FirstPlugin.class, factories.get(0).createPlugin(invocationInfo()));
    }

    private static InvocationInfo invocationInfo() {
        return new InvocationInfo("req-123", "arn:test", true, Instant.now());
    }

    private static TestProvider provider(String name, Supplier<DurableExecutionPlugin> pluginSupplier) {
        return new TestProvider(name, pluginSupplier);
    }

    private record TestProvider(String name, Supplier<DurableExecutionPlugin> pluginSupplier)
            implements DurableExecutionPluginProvider {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
            return pluginSupplier.get();
        }
    }

    private static final class ExplicitPlugin implements DurableExecutionPlugin {}

    private static final class FirstPlugin implements DurableExecutionPlugin {}

    private static final class SecondPlugin implements DurableExecutionPlugin {}

    /** A provider whose {@code createPlugin} implementation is inherited from a superclass. */
    private abstract static class BaseProvider implements DurableExecutionPluginProvider {

        @Override
        public DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
            return new FirstPlugin();
        }
    }

    private static final class InheritsFromBaseProvider extends BaseProvider {

        @Override
        public String getName() {
            return "inherits-from-base";
        }
    }

    /** A provider whose {@code createPlugin} implementation is inherited as a default method. */
    private interface DefaultMethodProvider extends DurableExecutionPluginProvider {

        @Override
        default DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
            return new FirstPlugin();
        }
    }

    private static final class InheritsDefaultMethodProvider implements DefaultMethodProvider {

        @Override
        public String getName() {
            return "inherits-default-method";
        }
    }

    /** A provider that declares {@code createPlugin} with a narrowed return type. */
    private static final class NarrowedReturnTypeProvider implements DurableExecutionPluginProvider {

        @Override
        public String getName() {
            return "narrowed-return-type";
        }

        @Override
        public FirstPlugin createPlugin(InvocationInfo invocationInfo) {
            return new FirstPlugin();
        }
    }
}
