// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import java.util.Locale;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;

/**
 * 10-10: Plugin operation-start and operation-end hooks fire for wait-type operations.
 *
 * <p>A single 2-second wait. The plugin, filtering to wait-type operations, logs operation-start when the wait's
 * STARTED checkpoint is observed and operation-end with the terminal status. The type token is normalized to upper-case
 * (WAIT).
 */
public class PluginWaitOperationHooks extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new WaitHooksPlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofSeconds(2));
        return "Wait completed";
    }

    private static final class WaitHooksPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            if (!PluginSupport.isWait(info.type())) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-start\", \"op\": \"%s\", \"type\": \"%s\"%s}",
                    info.id(), info.type().toUpperCase(Locale.ROOT), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isWait(info.type())) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-end\", \"op\": \"%s\", \"type\": \"%s\", "
                            + "\"status\": \"%s\"%s}",
                    info.id(),
                    info.type().toUpperCase(Locale.ROOT),
                    info.status(),
                    PluginSupport.arnField(executionArn)));
        }
    }
}
