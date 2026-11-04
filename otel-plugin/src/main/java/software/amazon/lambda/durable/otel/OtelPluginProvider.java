// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
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
    private final DurableExecutionPluginFactory factory = OtelPlugin.factory();

    @Override
    public String getName() {
        return "otel";
    }

    @Override
    public DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
        return factory.createPlugin(invocationInfo);
    }

    @Override
    public String getExclusiveGroup() {
        return factory.getExclusiveGroup();
    }

    /**
     * Names the selected entry and the view it loads, so an exclusive-group conflict reports the
     * {@code DURABLE_EXECUTION_PLUGINS} value the operator set rather than an object identity hash.
     */
    @Override
    public String toString() {
        return getName() + " (" + factory + ")";
    }
}
