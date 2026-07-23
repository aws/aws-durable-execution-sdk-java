// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * 10-5: Multiple registered plugins all receive lifecycle hooks.
 *
 * <p>A single greeting step configured with TWO {@link ConformanceLoggingPlugin} instances registered together in
 * order A, B. Plugin A logs with prefix {@code CONFPLUGIN-A}, plugin B with prefix {@code CONFPLUGIN-B}. Both plugins
 * must receive the invocation-start and invocation-end hooks exactly once.
 */
@SuppressWarnings("deprecation")
public class PluginMultiplePlugins extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(
                        new ConformanceLoggingPlugin("CONFPLUGIN-A"),
                        new ConformanceLoggingPlugin("CONFPLUGIN-B"))
                .build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "Hello, " + input + "!");
    }
}
