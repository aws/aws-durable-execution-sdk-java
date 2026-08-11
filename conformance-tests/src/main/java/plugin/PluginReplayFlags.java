// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * 10-13: Non-terminal operations replay with replay=true; terminal operations are not re-emitted.
 *
 * <p>Two sequential steps. Step A succeeds on its first attempt (terminal before the retry invocation). Step B fails on
 * its first attempt and succeeds on the second, using the SDK's built-in exponential-backoff retry strategy (~1s
 * delay). The plugin, filtering to step-type operations, logs operation-start with the SDK's is-replayed indicator
 * ({@code OperationInfo#isReplay()}) and operation-end with the terminal status.
 */
public class PluginReplayFlags extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new ReplayFlagPlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.step("step-a", String.class, stepCtx -> "a");
        return context.step(
                "step-b",
                String.class,
                stepCtx -> {
                    if (stepCtx.getAttempt() < 2) {
                        throw new RuntimeException("Attempt " + stepCtx.getAttempt() + " failed");
                    }
                    return "Operation succeeded";
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.exponentialBackoff(
                                3, Duration.ofSeconds(1), Duration.ofSeconds(10), 1.0, JitterStrategy.NONE))
                        .build());
    }

    private static final class ReplayFlagPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-start\", \"op\": \"%s\", \"replay\": %b%s}",
                    info.id(), info.isReplay(), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-end\", \"op\": \"%s\", \"status\": \"%s\"%s}",
                    info.id(), info.status(), PluginSupport.arnField(executionArn)));
        }
    }
}
