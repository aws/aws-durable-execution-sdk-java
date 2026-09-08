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
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/**
 * 10-17: A plugin that throws in every hook never blocks another plugin's hooks or the execution.
 *
 * <p>A single greeting step configured with TWO plugins registered together, in order: first a faulty plugin whose
 * every exercised hook (invocation-start, operation-start, attempt-start, attempt-end, operation-end, invocation-end)
 * logs a record then throws, then a healthy plugin that logs the corresponding six records normally. The SDK's
 * {@code PluginRunner} isolates each plugin at every hook boundary (swallows the faulty plugin's exceptions), so the
 * healthy plugin still receives every hook and the execution result/history are identical to running without the faulty
 * plugin. Attempt boundaries are the real user-function hooks ({@code onUserFunctionStart}/{@code onUserFunctionEnd},
 * filtered to step attempts); the healthy attempt-end reports the SDK's real success/failure outcome.
 */
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

    /** Every exercised invocation/operation/attempt hook logs its record then throws; the SDK must swallow it. */
    private static final class FaultyPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
            logAndThrow("invocation-start");
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            logAndThrow("operation-start");
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            logAndThrow("attempt-start");
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            logAndThrow("attempt-end");
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            logAndThrow("operation-end");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            logAndThrow("invocation-end");
        }

        /** Logs the hook record then throws, exercising the SDK's real per-hook plugin isolation. */
        private void logAndThrow(String hook) {
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-FAULTY\", \"hook\": \"%s\"%s}",
                    hook, PluginSupport.arnField(executionArn)));
            throw new RuntimeException("faulty " + hook);
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
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-HEALTHY\", \"hook\": \"attempt-start\", \"op\": \"%s\"%s}",
                    info.id(), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN-HEALTHY\", \"hook\": \"attempt-end\", \"op\": \"%s\", "
                            + "\"outcome\": \"%s\"%s}",
                    info.id(), info.outcome().name(), PluginSupport.arnField(executionArn)));
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
