// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;

/**
 * 10-11: Plugin operation hooks report parent id for a step nested in a child context.
 *
 * <p>A child context containing a single greeting step. The plugin logs operation-end for every operation that reaches
 * a terminal status, with the operation id, its parent id (the literal string {@code NONE} when absent), and status.
 * The inner step ends with the child context as its parent; the child context ends with no parent.
 */
public class PluginNestedParentLinkage extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new ParentLinkagePlugin()).build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.runInChildContext(
                "child", String.class, child -> child.step("greet", String.class, stepCtx -> "Hello, " + input + "!"));
    }

    private static final class ParentLinkagePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-end\", \"op\": \"%s\", \"parent\": \"%s\", "
                            + "\"status\": \"%s\"%s}",
                    info.id(),
                    PluginSupport.parentOrNone(info.parentId()),
                    info.status(),
                    PluginSupport.arnField(executionArn)));
        }
    }
}
