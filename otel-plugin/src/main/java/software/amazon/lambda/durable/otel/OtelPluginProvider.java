// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

/** Loads the default execution view when {@code DURABLE_EXECUTION_PLUGINS} contains {@code otel}. */
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
}
