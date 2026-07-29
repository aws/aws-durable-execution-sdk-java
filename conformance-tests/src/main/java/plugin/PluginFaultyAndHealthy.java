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
import software.amazon.lambda.durable.plugin.OperationInfo;

/**
 * 10-17: A plugin that throws in every hook never blocks another plugin's hooks or the execution.
 *
 * <p>A single greeting step configured with TWO plugins registered together, in order: first a faulty plugin whose
 * every invocation hook logs a line then throws, then a healthy plugin that logs normally. The SDK's {@code
 * PluginRunner} isolates each plugin (swallows the faulty plugin's exceptions), so the healthy plugin still receives
 * every hook and the execution result/history are identical to running without the faulty plugin.
 */
@SuppressWarnings("deprecation")
public class PluginFaultyAndHealthy extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(new FaultyPlugin(), new HealthyPlugin())
                .build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "Hello, " + input + "!");
    }

    /** Every invocation hook logs its line then throws; the SDK must swallow the exception. */
    private static final class FaultyPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-FAULTY\", \"hook\": \"invocation-start\"%s}",
                    PluginSupport.arnField(executionArn)));
            throw new RuntimeException("faulty invocation-start");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-FAULTY\", \"hook\": \"invocation-end\"%s}",
                    PluginSupport.arnField(executionArn)));
            throw new RuntimeException("faulty invocation-end");
        }
    }

    /** Logs normally; must receive every hook despite the faulty peer. */
    private static final class HealthyPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-HEALTHY\", \"hook\": \"invocation-start\", \"first\": %b%s}",
                    info.isFirstInvocation(), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-HEALTHY\", \"hook\": \"invocation-end\", \"status\": \"%s\"%s}",
                    info.invocationStatus().name(), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-HEALTHY\", \"hook\": \"operation-start\", \"op\": \"%s\"%s}",
                    info.id(), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-HEALTHY\", \"hook\": \"operation-end\", \"op\": \"%s\", "
                            + "\"status\": \"%s\"%s}",
                    info.id(), info.status(), PluginSupport.arnField(executionArn)));
        }
    }
}
