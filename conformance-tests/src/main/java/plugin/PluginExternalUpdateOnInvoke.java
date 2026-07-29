// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;

/**
 * 10-9: Plugin observes a wait completed externally between invocations as an updated operation.
 *
 * <p>A single 2-second wait. The Java SDK surfaces an operation that completed while the execution was suspended via
 * {@code onOperationEnd} with {@code isReplay() == true} (per the hook contract: a later invocation that first observes
 * a completion which happened during suspension). The plugin captures {@code isFirstInvocation} from the
 * invocation-start hook and emits the "updated-on-invoke" record only for wait-type operations observed with
 * {@code isReplay() == true}; on the first invocation the wait has only started (never ends), so nothing is emitted.
 */
@SuppressWarnings("deprecation")
public class PluginExternalUpdateOnInvoke extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new UpdatedOnInvokePlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofSeconds(2));
        return "Wait completed";
    }

    private static final class UpdatedOnInvokePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;
        private volatile boolean firstInvocation;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
            this.firstInvocation = info.isFirstInvocation();
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isWait(info.type()) || !info.isReplay()) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"updated-on-invoke\", \"op\": \"%s\", "
                            + "\"status\": \"%s\", \"first\": %b%s}",
                    info.id(), info.status(), firstInvocation, PluginSupport.arnField(executionArn)));
        }
    }
}
