// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * 10-8: Plugin operation-change hook reports updated operations and the full operation map.
 *
 * <p>A single greeting step. The plugin implements the real {@code onOperationChange} hook and, for each step-type
 * operation in the change's updated-operations delta, logs the operation id, its post-change status, and whether that
 * id is also present in the change info's full operation map.
 */
public class PluginOperationChange extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new ChangePlugin()).build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "Hello, " + input + "!");
    }

    private static final class ChangePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationChange(OperationChangeInfo info) {
            for (OperationChangeItemInfo item : info.updatedOperations().values()) {
                if (!PluginSupport.isStepChange(item.type())) {
                    continue;
                }
                boolean inFullMap = info.operations().containsKey(item.id());
                String status = item.status() != null ? item.status().toString() : "NONE";
                System.out.println(String.format(
                        "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-change\", \"op\": \"%s\", "
                                + "\"status\": \"%s\", \"in_full_map\": %b%s}",
                        item.id(), status, inFullMap, PluginSupport.arnField(executionArn)));
            }
        }
    }
}
