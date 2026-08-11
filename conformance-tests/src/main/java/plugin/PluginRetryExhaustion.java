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
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * 10-15: Attempt hooks fire for every attempt until exhaustion, then operation-end reports FAILED.
 *
 * <p>A single step that always throws, configured with the SDK's built-in exponential-backoff retry strategy allowing 2
 * total attempts (1 initial + 1 retry, ~1s delay). The plugin, filtering to step-type operations, logs attempt-start
 * and attempt-end (with outcome) from the real user-function hooks (which carry the 1-based attempt number) and
 * operation-end when the step reaches its terminal FAILED status.
 */
public class PluginRetryExhaustion extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new AttemptPlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "always-fails",
                String.class,
                stepCtx -> {
                    throw new RuntimeException("boom");
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.exponentialBackoff(
                                2, Duration.ofSeconds(1), Duration.ofSeconds(10), 1.0, JitterStrategy.NONE))
                        .build());
    }

    private static final class AttemptPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"attempt-start\", \"n\": %d, \"op\": \"%s\"%s}",
                    info.attempt(), info.id(), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            String outcome = info.succeeded() ? "SUCCEEDED" : "FAILED";
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"attempt-end\", \"n\": %d, \"outcome\": \"%s\", "
                            + "\"op\": \"%s\"%s}",
                    info.attempt(), outcome, info.id(), PluginSupport.arnField(executionArn)));
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
