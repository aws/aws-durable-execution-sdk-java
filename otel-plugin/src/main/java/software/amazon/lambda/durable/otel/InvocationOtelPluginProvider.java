// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;

/**
 * Dynamically loads {@link InvocationOtelPlugin} when {@code DURABLE_EXECUTION_PLUGINS} contains
 * {@code otel-invocation}.
 */
public final class InvocationOtelPluginProvider implements DurableExecutionPluginProvider {

    @Override
    public String getName() {
        return "otel-invocation";
    }

    @Override
    public int getApiVersion() {
        return API_VERSION;
    }

    @Override
    public Class<? extends DurableExecutionPlugin> getPluginType() {
        return InvocationOtelPlugin.class;
    }

    @Override
    public DurableExecutionPlugin createPlugin() {
        return new InvocationOtelPlugin();
    }
}
