// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * 10-7: Plugin invocation-end hook receives FAILED status when execution fails.
 *
 * <p>A single step that always throws, configured with no retries and {@link ConformanceLoggingPlugin}. The plugin
 * logs {@code invocation-start first=true}; the step throws, no retry is attempted, and the plugin's invocation-end
 * hook fires with {@code status=FAILED}.
 */
@SuppressWarnings("deprecation")
public class PluginTerminalFailure extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(new ConformanceLoggingPlugin("CONFPLUGIN"))
                .build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "failing-step",
                String.class,
                stepCtx -> {
                    throw new RuntimeException("Something went wrong");
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build());
    }
}
