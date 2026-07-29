// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;

/**
 * 10-18: Slow work in a plugin hook completes and its record is emitted before the invocation ends.
 *
 * <p>A single greeting step. The plugin, filtering to step-type operations, performs ~1 second of deliberately slow
 * work directly inside its real {@code onOperationEnd} hook and only then logs its record. The Java SDK dispatches
 * operation hooks synchronously through {@code PluginRunner} on the invocation's own execution path, so the sleep
 * blocks that path and the record must be flushed before the Lambda response is returned (and the environment
 * freezes) — this uses the SDK's natural hook-completion contract, not a mock. The invocation-end hook (which the SDK
 * explicitly awaits) also fires with the terminal status.
 */
@SuppressWarnings("deprecation")
public class PluginSlowHookFlush extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new SlowHookPlugin()).build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "Hello, " + input + "!");
    }

    private static final class SlowHookPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            // ~1s of real work inside the hook, using the SDK's hook-completion contract.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"slow-operation-end\", \"op\": \"%s\", \"status\": \"%s\"%s}",
                    info.id(), info.status(), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"invocation-end\", \"status\": \"%s\"%s}",
                    info.invocationStatus().name(), PluginSupport.arnField(executionArn)));
        }
    }
}
