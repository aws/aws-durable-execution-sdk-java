// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.util.ArrayList;
import java.util.List;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/**
 * 10-12: Plugin user-function hooks fire per parallel branch with parent linkage.
 *
 * <p>A parallel operation named "parallel" with two branches (max-concurrency 1, so they run sequentially in index
 * order); each branch returns a constant directly. The plugin, filtering to parallel-branch operations, logs fn-start
 * and fn-end (with outcome) from the real user-function hooks, carrying the branch operation id and the parallel parent
 * id. These hooks run on the branch's own thread, so start-before-end order per branch is deterministic.
 */
public class PluginParallelBranchHooks extends DurableHandler<Object, List<String>> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new BranchHooksPlugin()).build();
    }

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("parallel", config);
        try (parallel) {
            futures.add(parallel.branch("branch-0", String.class, branch -> "task-1"));
            futures.add(parallel.branch("branch-1", String.class, branch -> "task-2"));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }

    private static final class BranchHooksPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            if (!PluginSupport.isBranch(info.subType())) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"fn-start\", \"op\": \"%s\", \"parent\": \"%s\"%s}",
                    info.id(), PluginSupport.parentOrNone(info.parentId()), PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            if (!PluginSupport.isBranch(info.subType())) {
                return;
            }
            String outcome = info.succeeded() ? "SUCCEEDED" : "FAILED";
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"fn-end\", \"op\": \"%s\", \"parent\": \"%s\", "
                            + "\"outcome\": \"%s\"%s}",
                    info.id(),
                    PluginSupport.parentOrNone(info.parentId()),
                    outcome,
                    PluginSupport.arnField(executionArn)));
        }
    }
}
