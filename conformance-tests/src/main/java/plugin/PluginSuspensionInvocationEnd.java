// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

/**
 * 10-16: Invocation-end fires for every invocation, non-terminal on suspension and terminal at completion.
 *
 * <p>A single 2-second wait. The plugin logs invocation-start (with the first-invocation flag) and invocation-end,
 * where {@code terminal} is computed by the plugin as whether the reported invocation status is SUCCEEDED or FAILED.
 * The suspending first invocation reports a non-terminal status (PENDING → terminal=false); the resuming invocation
 * reports terminal SUCCEEDED (terminal=true).
 */
public class PluginSuspensionInvocationEnd extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new InvocationEndPlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofSeconds(2));
        return "Wait completed";
    }

    private static final class InvocationEndPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"invocation-start\", \"first\": %b%s}",
                    info.isFirstInvocation(), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            InvocationStatus status = info.invocationStatus();
            boolean terminal = status == InvocationStatus.SUCCEEDED || status == InvocationStatus.FAILED;
            // Same-invocation flag from the SDK's own end-hook info: ties this record to the invocation that
            // emitted it.
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"invocation-end\", \"first\": %b, \"terminal\": %b, "
                            + "\"status\": \"%s\"%s}",
                    info.isFirstInvocation(), terminal, status.name(), PluginSupport.arnField(executionArn)));
        }
    }
}
