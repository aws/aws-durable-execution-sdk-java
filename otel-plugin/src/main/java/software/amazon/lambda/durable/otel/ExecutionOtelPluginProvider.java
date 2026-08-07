// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;

/**
 * Dynamically loads {@link ExecutionOtelPlugin} when {@code DURABLE_EXECUTION_PLUGINS} contains {@code otel-execution}.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class ExecutionOtelPluginProvider implements DurableExecutionPluginProvider {

    @Override
    public String getName() {
        return "otel-execution";
    }

    @Override
    public int getApiVersion() {
        return API_VERSION;
    }

    @Override
    public Class<? extends DurableExecutionPlugin> getPluginType() {
        return ExecutionOtelPlugin.class;
    }

    @Override
    public DurableExecutionPlugin createPlugin() {
        return new ExecutionOtelPlugin();
    }
}
