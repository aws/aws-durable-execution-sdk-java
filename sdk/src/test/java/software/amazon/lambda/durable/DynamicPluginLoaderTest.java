// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;

class DynamicPluginLoaderTest {

    @Test
    void unsetConfigurationPreservesExplicitPluginsWithoutDiscoveringProviders() {
        var explicitPlugin = new FirstPlugin();
        Iterable<DurableExecutionPluginProvider> providers = () -> {
            throw new AssertionError("Providers should not be discovered");
        };

        var plugins = DynamicPluginLoader.loadConfiguredPlugins(null, providers, List.of(explicitPlugin));

        assertEquals(1, plugins.size());
        assertSame(explicitPlugin, plugins.get(0));
    }

    @Test
    void loadsRequestedProvidersBeforeExplicitPluginsInConfiguredOrder() {
        var creationOrder = new ArrayList<String>();
        var explicitPlugin = new ExplicitPlugin();
        var firstProvider = provider("first", FirstPlugin.class, () -> {
            creationOrder.add("first");
            return new FirstPlugin();
        });
        var secondProvider = provider("second", SecondPlugin.class, () -> {
            creationOrder.add("second");
            return new SecondPlugin();
        });

        var plugins = DynamicPluginLoader.loadConfiguredPlugins(
                " second, first ", List.of(firstProvider, secondProvider), List.of(explicitPlugin));

        assertInstanceOf(SecondPlugin.class, plugins.get(0));
        assertInstanceOf(FirstPlugin.class, plugins.get(1));
        assertSame(explicitPlugin, plugins.get(2));
        assertEquals(List.of("second", "first"), creationOrder);
    }

    @Test
    void doesNotCreateProvidersOutsideTheAllowList() {
        var unrequestedCreations = new AtomicInteger();
        var requestedProvider = provider("requested", FirstPlugin.class, FirstPlugin::new);
        var unrequestedProvider = provider("unrequested", SecondPlugin.class, () -> {
            unrequestedCreations.incrementAndGet();
            return new SecondPlugin();
        });

        var plugins = DynamicPluginLoader.loadConfiguredPlugins(
                "requested", List.of(requestedProvider, unrequestedProvider), List.of());

        assertEquals(1, plugins.size());
        assertEquals(0, unrequestedCreations.get());
    }

    @Test
    void loadsExplicitAndDynamicPluginsOfTheSameType() {
        var creations = new AtomicInteger();
        var explicitPlugin = new FirstPlugin();
        var dynamicPlugin = new FirstPlugin();
        var duplicateProvider = provider("first", FirstPlugin.class, () -> {
            creations.incrementAndGet();
            return dynamicPlugin;
        });

        var plugins =
                DynamicPluginLoader.loadConfiguredPlugins("first", List.of(duplicateProvider), List.of(explicitPlugin));

        assertEquals(2, plugins.size());
        assertSame(dynamicPlugin, plugins.get(0));
        assertSame(explicitPlugin, plugins.get(1));
        assertEquals(1, creations.get());
    }

    @Test
    void rejectsEmptyConfiguredProviderName() {
        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPlugins("first,,second", List.of(), List.of()));

        assertTrue(error.getMessage().contains("must be non-empty"));
        assertTrue(error.getMessage().contains(DynamicPluginLoader.PLUGINS_ENVIRONMENT_VARIABLE));
    }

    @Test
    void rejectsDuplicateConfiguredProviderName() {
        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPlugins("first,first", List.of(), List.of()));

        assertTrue(error.getMessage().contains("listed more than once"));
    }

    @Test
    void rejectsUnknownProviderAndListsAvailableNames() {
        var availableProvider = provider("available", FirstPlugin.class, FirstPlugin::new);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPlugins("missing", List.of(availableProvider), List.of()));

        assertTrue(error.getMessage().contains("No DurableExecutionPluginProvider named 'missing'"));
        assertTrue(error.getMessage().contains("available"));
    }

    @Test
    void rejectsDuplicateDiscoveredProviderNames() {
        var firstProvider = provider("duplicate", FirstPlugin.class, FirstPlugin::new);
        var secondProvider = provider("duplicate", SecondPlugin.class, SecondPlugin::new);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPlugins(
                        "duplicate", List.of(firstProvider, secondProvider), List.of()));

        assertTrue(error.getMessage().contains("Multiple DurableExecutionPluginProvider implementations"));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void rejectsIncompatibleProviderApiVersion() {
        var provider = new TestProvider("first", 2, FirstPlugin.class, FirstPlugin::new);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPlugins("first", List.of(provider), List.of()));

        assertTrue(error.getMessage().contains("uses provider API version 2"));
        assertTrue(error.getMessage().contains("requires version " + DurableExecutionPluginProvider.API_VERSION));
    }

    @Test
    void rejectsInvalidDeclaredPluginType() {
        var provider = provider("invalid", DurableExecutionPlugin.class, FirstPlugin::new);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPlugins("invalid", List.of(provider), List.of()));

        assertTrue(error.getMessage().contains("must declare a concrete DurableExecutionPlugin type"));
    }

    @Test
    void rejectsPluginThatDoesNotMatchDeclaredType() {
        var provider = provider("first", FirstPlugin.class, SecondPlugin::new);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPlugins("first", List.of(provider), List.of()));

        assertTrue(error.getMessage().contains("declared type"));
        assertTrue(error.getMessage().contains(SecondPlugin.class.getName()));
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
                () -> DynamicPluginLoader.loadConfiguredPlugins("first", providers, List.of()));

        assertTrue(error.getMessage().contains("Failed to discover"));
        assertInstanceOf(LinkageError.class, error.getCause());
    }

    @Test
    void wrapsPluginCreationFailure() {
        var provider = provider("first", FirstPlugin.class, () -> {
            throw new IllegalArgumentException("bad settings");
        });

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPlugins("first", List.of(provider), List.of()));

        assertTrue(error.getMessage().contains("failed to create its plugin"));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    private static TestProvider provider(
            String name,
            Class<? extends DurableExecutionPlugin> pluginType,
            Supplier<DurableExecutionPlugin> pluginSupplier) {
        return new TestProvider(name, DurableExecutionPluginProvider.API_VERSION, pluginType, pluginSupplier);
    }

    private record TestProvider(
            String name,
            int apiVersion,
            Class<? extends DurableExecutionPlugin> pluginType,
            Supplier<DurableExecutionPlugin> pluginSupplier)
            implements DurableExecutionPluginProvider {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getApiVersion() {
            return apiVersion;
        }

        @Override
        public Class<? extends DurableExecutionPlugin> getPluginType() {
            return pluginType;
        }

        @Override
        public DurableExecutionPlugin createPlugin() {
            return pluginSupplier.get();
        }
    }

    private static final class ExplicitPlugin implements DurableExecutionPlugin {}

    private static final class FirstPlugin implements DurableExecutionPlugin {}

    private static final class SecondPlugin implements DurableExecutionPlugin {}
}
