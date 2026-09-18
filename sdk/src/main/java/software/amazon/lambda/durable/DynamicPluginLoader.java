// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

final class DynamicPluginLoader {
    static final String PLUGINS_ENVIRONMENT_VARIABLE = "DURABLE_EXECUTION_PLUGINS";

    /**
     * Closing sentences shared by the failures a stale provider JAR produces. A provider distributed as a Lambda layer
     * has its own version and its own deployment, so raising the function's SDK dependency leaves the deployed provider
     * untouched. An operator who does not know that reads "rebuild the provider" as something the function build
     * already did, so the remedy names the layer explicitly.
     */
    private static final String REBUILD_PROVIDER_REMEDY =
            "Rebuild the provider against this SDK version and redeploy it. A provider shipped as a Lambda layer is "
                    + "versioned and deployed separately from the function package, so upgrading the function's SDK "
                    + "dependency does not update the layer.";

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
        requireCreatePluginImplementation(name, provider);
        return provider;
    }

    /**
     * Fails when a selected provider does not implement
     * {@link DurableExecutionPluginFactory#createPlugin(InvocationInfo)}.
     *
     * <p>A provider JAR compiled against an SDK version whose provider interface declared a different
     * {@code createPlugin} method still loads. Its class file references nothing this version removed, so
     * {@link ServiceLoader} instantiates it, {@link DurableExecutionPluginProvider#getName()} returns its name, and
     * selection by name succeeds. The first call to {@code createPlugin(InvocationInfo)} then throws
     * {@link AbstractMethodError}, which is contained per invocation and logged as a warning. Without this check the
     * function keeps succeeding while the provider emits nothing, and the only signal is one warning per invocation.
     * This check reports the condition as a startup failure instead, which is how every other provider configuration
     * problem on this path is already reported.
     *
     * <p>{@link Class#getMethod} resolves to the most specific declaration reachable from the runtime class. A provider
     * that declares the method itself, inherits a concrete implementation from a superclass, or inherits a default
     * implementation from a subinterface of {@link DurableExecutionPluginFactory} therefore resolves to a non-abstract
     * method. A provider that has none of those resolves to the abstract declaration on
     * {@link DurableExecutionPluginFactory} itself. The abstract modifier on the resolved method distinguishes the two
     * cases, and reading it runs no provider code.
     *
     * <p>A class that inherits a {@code createPlugin(InvocationInfo)} default from an interface unrelated to
     * {@link DurableExecutionPluginFactory} does not compile, because an unrelated default does not override the
     * factory interface's abstract declaration. That shape cannot reach this check from Java source.
     *
     * <p>The provider reaching this method was already cast to this SDK's {@link DurableExecutionPluginProvider}, so it
     * inherits this SDK's {@code createPlugin(InvocationInfo)} declaration and {@link Class#getMethod} finds at least
     * that declaration. A failure to resolve the method therefore means the provider's class hierarchy resolves
     * {@link InvocationInfo} to a different class than this SDK does, which is a class path problem with the same
     * remedy. It is reported as a configuration failure rather than allowed to escape configuration as an unexplained
     * {@link NoSuchMethodException}.
     */
    private static void requireCreatePluginImplementation(String name, DurableExecutionPluginProvider provider) {
        var providerClass = provider.getClass();
        Method createPlugin;
        try {
            createPlugin = providerClass.getMethod("createPlugin", InvocationInfo.class);
        } catch (NoSuchMethodException | LinkageError e) {
            throw configurationError(
                    "Plugin provider '" + name + "' (" + describe(providerClass)
                            + ") does not expose a createPlugin method that accepts this SDK's InvocationInfo type. "
                            + REBUILD_PROVIDER_REMEDY,
                    e);
        }
        if (Modifier.isAbstract(createPlugin.getModifiers())) {
            throw configurationError("Plugin provider '" + name + "' (" + describe(providerClass)
                    + ") does not implement createPlugin(InvocationInfo). It was compiled against an older "
                    + "Durable Execution SDK whose provider interface declared a different createPlugin method. "
                    + REBUILD_PROVIDER_REMEDY);
        }
    }

    /** Returns the provider class name, with the artifact it was loaded from when the JVM reports one. */
    private static String describe(Class<?> providerClass) {
        var location = codeSourceLocation(providerClass);
        return location == null ? providerClass.getName() : providerClass.getName() + " from " + location;
    }

    /**
     * Returns the location of the artifact a class was loaded from, or null when the JVM does not report one.
     *
     * <p>A class defined by a loader that supplies no code source has no location, and a security manager can refuse
     * the protection domain. Neither case says anything about whether the provider is usable, so neither may replace
     * the configuration failure being reported. Both are therefore reported as an absent location.
     */
    private static String codeSourceLocation(Class<?> providerClass) {
        try {
            var protectionDomain = providerClass.getProtectionDomain();
            var codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
            var location = codeSource == null ? null : codeSource.getLocation();
            return location == null ? null : location.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IllegalStateException configurationError(String message) {
        return new IllegalStateException("Dynamic plugin configuration failed: " + message);
    }

    private static IllegalStateException configurationError(String message, Throwable cause) {
        return new IllegalStateException("Dynamic plugin configuration failed: " + message, cause);
    }
}
