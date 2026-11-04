// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

/**
 * Dynamically loads {@link InvocationOtelPlugin} when {@code DURABLE_EXECUTION_PLUGINS} contains
 * {@code otel-invocation}.
 *
 * <p>The provider is itself the per-invocation factory: it holds the environment-lifetime state (the ADOT global
 * provider binding, the ID generator) once and creates one plugin instance per invocation from it.
 */
public final class InvocationOtelPluginProvider implements DurableExecutionPluginProvider {

    private final DurableExecutionPluginProvider delegate =
            new DelegatingOtelPluginProvider("otel-invocation", InvocationOtelPlugin.factory());

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
        return delegate.createPlugin(invocationInfo);
    }

    @Override
    public String getExclusiveGroup() {
        return delegate.getExclusiveGroup();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
