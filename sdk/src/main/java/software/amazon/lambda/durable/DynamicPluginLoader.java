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
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
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
     * <p>What is checked is the condition {@code invokeinterface} itself needs: a public, non-static, non-abstract
     * method named {@code createPlugin} taking this SDK's {@link InvocationInfo} and returning exactly
     * {@link DurableExecutionPlugin}, which is the erased descriptor the interface declares. Checking anything looser
     * accepts class files the call cannot dispatch to. A concrete {@code MyPlugin createPlugin(InvocationInfo)} that
     * overrides nothing -- which is what a class compiled against an older interface declares -- is such a file: its
     * return type is a {@link DurableExecutionPlugin} subtype, so an assignability test passes it, while the interface
     * call still finds no matching descriptor and throws.
     *
     * <p>Requiring the exact descriptor cannot reject a provider that would have worked, because the descriptor is what
     * dispatch resolves. A covariant override compiles to the specific method plus a bridge that returns
     * {@link DurableExecutionPlugin}, and it is the bridge the interface call reaches; a compiler that emitted no
     * bridge would produce a class the JVM cannot dispatch to either. The whole public method set is examined rather
     * than the one {@link Class#getMethod} resolves, because that resolution prefers the most specific return type and
     * so hides the bridge behind the covariant declaration, and because it searches the class before the interfaces and
     * so returns a static same-signature helper in preference to the interface's declaration.
     *
     * <p>Every property read is a class-file property, so this runs no provider code.
     *
     * <p>{@link Class#getMethods} can raise a {@link LinkageError} while resolving a method's parameter or return type
     * against a class path that cannot supply it. That is a class path problem with the same remedy, so it is reported
     * as this configuration failure rather than escaping as an unexplained {@code NoClassDefFoundError}.
     */
    private static void requireCreatePluginImplementation(String name, DurableExecutionPluginProvider provider) {
        var providerClass = provider.getClass();
        String reason;
        try {
            reason = undispatchableCreatePluginReason(providerClass);
        } catch (LinkageError e) {
            throw configurationError(
                    "Plugin provider '" + name + "' (" + describe(providerClass)
                            + ") declares a createPlugin method whose types this class path cannot resolve. "
                            + REBUILD_PROVIDER_REMEDY,
                    e);
        }
        if (reason != null) {
            throw configurationError("Plugin provider '" + name + "' (" + describe(providerClass)
                    + ") does not implement createPlugin(InvocationInfo): " + reason
                    + ". It was compiled against an older Durable Execution SDK whose provider interface declared a "
                    + "different createPlugin method. " + REBUILD_PROVIDER_REMEDY);
        }
    }

    /**
     * Returns why no public method can serve the interface call, or null when one can.
     *
     * <p>The reason names what was found instead, because an operator reading the failure has to be able to tell a
     * provider that predates the current interface from a class path that resolves {@link InvocationInfo} to two
     * different classes.
     */
    private static String undispatchableCreatePluginReason(Class<?> providerClass) {
        var abstractOn = (Class<?>) null;
        var staticFound = false;
        var otherReturnType = (Class<?>) null;
        for (var method : providerClass.getMethods()) {
            if (!isCreatePluginCandidate(method)) {
                continue;
            }
            var modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                staticFound = true;
            } else if (Modifier.isAbstract(modifiers)) {
                abstractOn = method.getDeclaringClass();
            } else if (method.getReturnType() == DurableExecutionPlugin.class) {
                return null;
            } else {
                otherReturnType = method.getReturnType();
            }
        }
        if (otherReturnType != null) {
            return "its createPlugin(InvocationInfo) returns " + otherReturnType.getName()
                    + " and the class carries no method returning " + DurableExecutionPlugin.class.getName()
                    + ", so it overrides nothing the interface call can dispatch to";
        }
        if (staticFound) {
            return "the createPlugin(InvocationInfo) it declares is static, so it cannot implement the interface's "
                    + "instance method";
        }
        if (abstractOn != null) {
            return "the only declaration is the abstract one on " + abstractOn.getName();
        }
        return "it declares no createPlugin method taking this SDK's " + InvocationInfo.class.getName();
    }

    /** Whether a method is named and parameterized like the factory method, whatever it returns. */
    private static boolean isCreatePluginCandidate(Method method) {
        return "createPlugin".equals(method.getName())
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == InvocationInfo.class;
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
