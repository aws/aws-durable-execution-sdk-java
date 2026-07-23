// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * 10-4: Plugin exceptions are swallowed and never affect the execution outcome.
 *
 * <p>A single greeting step configured with {@link FaultyConformancePlugin}, whose every hook logs a line and then
 * throws. The SDK must catch and ignore every plugin exception so the execution result and history are identical to
 * running without the plugin.
 */
@SuppressWarnings("deprecation")
public class PluginErrorIsolation extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new FaultyConformancePlugin()).build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "Hello, " + input + "!");
    }
}
