// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/**
 * Service provider interface for dynamically loading {@link DurableExecutionPlugin} implementations.
 *
 * <p>Provider JARs register implementations in
 * {@code META-INF/services/software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider}. The SDK only creates
 * plugins from providers explicitly selected through {@code DURABLE_EXECUTION_PLUGINS}.
 */
public interface DurableExecutionPluginProvider {

    /** Current version of the dynamic plugin provider contract. */
    int API_VERSION = 1;

    /**
     * Returns the stable name used to select this provider.
     *
     * @return non-empty provider name
     */
    String getName();

    /**
     * Returns the provider API version this implementation supports.
     *
     * @return provider API version
     */
    int getApiVersion();

    /**
     * Returns the concrete plugin type created by this provider.
     *
     * @return plugin implementation class
     */
    Class<? extends DurableExecutionPlugin> getPluginType();

    /**
     * Creates the plugin instance.
     *
     * @return plugin instance
     */
    DurableExecutionPlugin createPlugin();
}
