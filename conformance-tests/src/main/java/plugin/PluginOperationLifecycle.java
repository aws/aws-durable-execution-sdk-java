// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * 10-2: Plugin operation lifecycle hooks (step start and terminal end).
 *
 * <p>A single greeting step configured with {@link ConformanceLoggingPlugin}. The plugin, filtering to step-type
 * operations, logs {@code operation-start} when the step's STARTED checkpoint is observed and
 * {@code operation-end status=SUCCEEDED} when the step reaches its terminal status.
 */
@SuppressWarnings("deprecation")
public class PluginOperationLifecycle extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(new ConformanceLoggingPlugin("CONFPLUGIN"))
                .build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "Hello, " + input + "!");
    }
}
