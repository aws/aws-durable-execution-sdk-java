// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * 10-14: Operation-end info carries the checkpointed result on success and the error on failure.
 *
 * <p>Step A returns the constant "task-a" and succeeds; step B always throws "boom" with no retries, so the execution
 * fails. The plugin, filtering to step-type operations, logs operation-end with the operation's status, its
 * checkpointed serialized result, and its error message.
 *
 * <p>SDK CAPABILITY GAP (Java): {@link OperationEndInfo} exposes no serialized-result field — its record is {@code (id,
 * name, type, subType, parentId, startTimestamp, endTimestamp, status, attempt, isReplay, error)}. The result of a
 * successful operation is therefore not available to the hook, so this handler honestly logs {@code result: NONE} for
 * step A. The requirement expects {@code result: "task-a"}; that assertion will fail, which is the correct signal that
 * the Java plugin API does not surface operation results at the operation-end boundary. The error path (step B,
 * {@code error: boom}) is fully supported via {@link OperationEndInfo#error()}.
 */
@SuppressWarnings("deprecation")
public class PluginTerminalPayloads extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new PayloadPlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.step("step-a", String.class, stepCtx -> "task-a");
        return context.step(
                "step-b",
                String.class,
                stepCtx -> {
                    throw new RuntimeException("boom");
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build());
    }

    private static final class PayloadPlugin implements DurableExecutionPlugin {
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
            // OperationEndInfo has no serialized-result accessor in the Java SDK; honestly report NONE.
            String result = "NONE";
            String error = info.error() != null && info.error().getMessage() != null
                    ? info.error().getMessage()
                    : "NONE";
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-end\", \"op\": \"%s\", \"status\": \"%s\", "
                            + "\"result\": \"%s\", \"error\": \"%s\"%s}",
                    info.id(), info.status(), result, error, PluginSupport.arnField(executionArn)));
        }
    }
}
