// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/**
 * A {@link DurableExecutionPluginFactory} that can be discovered through {@link java.util.ServiceLoader} and selected
 * by name.
 *
 * <p>Provider JARs register implementations in
 * {@code META-INF/services/software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider}. The SDK only uses
 * providers explicitly selected through {@code DURABLE_EXECUTION_PLUGINS}; selection is by {@link #getName()}.
 *
 * <p>A provider is itself the per-invocation factory: {@link #createPlugin(InvocationInfo)} is called once per
 * invocation, and the returned instance serves only that invocation.
 */
public interface DurableExecutionPluginProvider extends DurableExecutionPluginFactory {

    /**
     * Returns the stable name used to select this provider.
     *
     * @return non-empty provider name
     */
    String getName();
}
