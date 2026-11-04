// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

/**
 * Loads the default execution view when {@code DURABLE_EXECUTION_PLUGINS} contains {@code otel}.
 *
 * <p>{@code otel} and {@code otel-execution} load the same view through separate providers, so listing both puts two
 * factories in the {@code durable-otel-view} exclusive group and configuration is rejected at startup. Set exactly one
 * OTel entry name.
 */
public final class OtelPluginProvider implements DurableExecutionPluginProvider {
    private final DurableExecutionPluginProvider delegate =
            new DelegatingOtelPluginProvider("otel", OtelPlugin.factory());

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
