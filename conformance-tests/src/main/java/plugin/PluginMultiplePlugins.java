// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;

/**
 * 10-5: Multiple registered plugins all receive lifecycle hooks.
 *
 * <p>A single greeting step configured with TWO invocation-logging plugins registered together in order A, B. Plugin A
 * logs with prefix {@code CONFPLUGIN-A}, plugin B with prefix {@code CONFPLUGIN-B}, emitting exactly the lines the
 * requirement documents: {@code <prefix> invocation-start} and {@code <prefix> invocation-end status=<status>}.
 */
@SuppressWarnings("deprecation")
public class PluginMultiplePlugins extends DurableHandler<String, String> {

    /** Minimal plugin emitting exactly the 10-5 documented invocation lines. */
    static final class InvocationLoggingPlugin implements DurableExecutionPlugin {
        private final String prefix;

        /** Captured from onInvocationStart; read by onInvocationEnd which may run on another thread. */
        private volatile String executionArn;

        InvocationLoggingPlugin(String prefix) {
            this.prefix = prefix;
        }

        /** Returns {@code , "durableExecutionArn": "<arn>"} when captured, otherwise an empty string. */
        private String arnField() {
            return executionArn == null ? "" : String.format(", \"durableExecutionArn\": \"%s\"", executionArn);
        }

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
            System.out.println(String.format(
                    "{\"plugin\": \"%s\", \"hook\": \"invocation-start\"%s}", prefix, arnField()));
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            System.out.println(String.format(
                    "{\"plugin\": \"%s\", \"hook\": \"invocation-end\", \"status\": \"%s\"%s}",
                    prefix, info.invocationStatus().name(), arnField()));
        }
    }

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(
                        new InvocationLoggingPlugin("CONFPLUGIN-A"), new InvocationLoggingPlugin("CONFPLUGIN-B"))
                .build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "Hello, " + input + "!");
    }
}
