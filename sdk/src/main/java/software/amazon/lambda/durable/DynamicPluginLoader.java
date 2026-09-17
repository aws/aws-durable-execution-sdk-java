// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;

final class DynamicPluginLoader {
    static final String PLUGINS_ENVIRONMENT_VARIABLE = "DURABLE_EXECUTION_PLUGINS";

    private DynamicPluginLoader() {}

    static List<DurableExecutionPluginFactory> loadConfiguredPluginFactories(
            List<DurableExecutionPluginFactory> explicitFactories) {
        var configuredNames = System.getenv(PLUGINS_ENVIRONMENT_VARIABLE);
        if (configuredNames == null || configuredNames.isBlank()) {
            return List.copyOf(explicitFactories);
        }

        var classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = DurableExecutionPluginProvider.class.getClassLoader();
        }
        return loadConfiguredPluginFactories(
                configuredNames,
                ServiceLoader.load(DurableExecutionPluginProvider.class, classLoader),
                explicitFactories);
    }

    static List<DurableExecutionPluginFactory> loadConfiguredPluginFactories(
            String configuredNames,
            Iterable<DurableExecutionPluginProvider> providers,
            List<DurableExecutionPluginFactory> explicitFactories) {
        if (configuredNames == null || configuredNames.isBlank()) {
            return List.copyOf(explicitFactories);
        }

        var requestedNames = parseProviderNames(configuredNames);
        var providersByName = indexProviders(providers);
        var factories = new ArrayList<DurableExecutionPluginFactory>();
        for (var name : requestedNames) {
            factories.add(getProvider(name, providersByName));
        }
        factories.addAll(explicitFactories);
        return List.copyOf(factories);
    }

    private static List<String> parseProviderNames(String configuredNames) {
        var names = new ArrayList<String>();
        var uniqueNames = new LinkedHashSet<String>();
        for (var configuredName : configuredNames.split(",", -1)) {
            var name = configuredName.trim();
            if (name.isEmpty()) {
                throw configurationError("Plugin provider names in " + PLUGINS_ENVIRONMENT_VARIABLE
                        + " must be non-empty comma-separated values");
            }
            if (!uniqueNames.add(name)) {
                throw configurationError(
                        "Plugin provider '" + name + "' is listed more than once in " + PLUGINS_ENVIRONMENT_VARIABLE);
            }
            names.add(name);
        }
        return names;
    }

    private static Map<String, DurableExecutionPluginProvider> indexProviders(
            Iterable<DurableExecutionPluginProvider> providers) {
        var providersByName = new LinkedHashMap<String, DurableExecutionPluginProvider>();
        try {
            for (var provider : providers) {
                if (provider == null) {
                    throw configurationError("ServiceLoader returned a null DurableExecutionPluginProvider");
                }
                var name = getProviderName(provider);
                var previous = providersByName.putIfAbsent(name, provider);
                if (previous != null) {
                    throw configurationError("Multiple DurableExecutionPluginProvider implementations use the name '"
                            + name + "': " + previous.getClass().getName() + " and "
                            + provider.getClass().getName());
                }
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            throw configurationError(
                    "Failed to discover DurableExecutionPluginProvider implementations. "
                            + "Verify that plugin JARs and the Durable Execution SDK use compatible versions",
                    e);
        }
        return providersByName;
    }

    private static String getProviderName(DurableExecutionPluginProvider provider) {
        String name;
        try {
            name = provider.getName();
        } catch (RuntimeException e) {
            throw configurationError(
                    "Plugin provider " + provider.getClass().getName() + " failed to return its name", e);
        }
        if (name == null || name.isBlank() || !name.equals(name.trim())) {
            throw configurationError("Plugin provider " + provider.getClass().getName()
                    + " returned an invalid name; names must be non-empty and must not have surrounding spaces");
        }
        return name;
    }

    private static DurableExecutionPluginProvider getProvider(
            String name, Map<String, DurableExecutionPluginProvider> providersByName) {
        var provider = providersByName.get(name);
        if (provider == null) {
            var available = providersByName.isEmpty() ? "none" : String.join(", ", providersByName.keySet());
            throw configurationError("No DurableExecutionPluginProvider named '" + name
                    + "' was found on the application class path. Available providers: " + available);
        }
        return provider;
    }

    private static IllegalStateException configurationError(String message) {
        return new IllegalStateException("Dynamic plugin configuration failed: " + message);
    }

    private static IllegalStateException configurationError(String message, Throwable cause) {
        return new IllegalStateException("Dynamic plugin configuration failed: " + message, cause);
    }
}
