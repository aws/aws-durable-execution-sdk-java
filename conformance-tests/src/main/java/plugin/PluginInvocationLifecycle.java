// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * 10-1: Plugin invocation lifecycle hooks (start and end on a single invocation).
 *
 * <p>A single greeting step configured with {@link ConformanceLoggingPlugin}. The plugin's invocation-start hook logs
 * {@code first=true} before the handler runs and its invocation-end hook logs {@code status=SUCCEEDED} after the
 * result is finalized. The step body logs its running line via the context logger.
 */
@SuppressWarnings("deprecation")
public class PluginInvocationLifecycle extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(new ConformanceLoggingPlugin("CONFPLUGIN"))
                .build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> {
            stepCtx.getLogger().info("Greeting step running for: {}", input);
            return "Hello, " + input + "!";
        });
    }
}
