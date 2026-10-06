// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;

/**
 * 10-18: Plugin replay flag for a non-terminal wait.
 *
 * <p>A parallel operation named "waits" with two branches running concurrently (max-concurrency 2): branch 0 runs a
 * wait named "short" of 2 seconds and returns "short-done"; branch 1 runs a wait named "long" of 8 seconds and returns
 * "long-done" (each wait's stable name is supplied via the SDK's real operation-name parameter). Both waits are pending
 * simultaneously in the first invocation. Filtering to wait-type operations, the plugin logs operation-start with the
 * stable wait name and the SDK's is-replayed indicator ({@code OperationInfo#isReplay()}), plus operation-end with the
 * wait name and terminal status. When the execution replays after the 2-second wait completes, the still-NON-terminal
 * 8-second wait MUST be re-observed with replay=true. Operation ids are deliberately not logged because branch event
 * ids are nondeterministic under concurrency; stable wait name + replay flag identify the behavior under test without
 * depending on warm-container state.
 */
@SuppressWarnings("deprecation")
public class PluginWaitReplayFlag extends DurableHandler<Object, List<String>> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new WaitReplayFlagPlugin()).build();
    }

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(2).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("waits", config);
        try (parallel) {
            futures.add(parallel.branch("branch-0", String.class, branch -> {
                branch.wait("short", Duration.ofSeconds(2));
                return "short-done";
            }));
            futures.add(parallel.branch("branch-1", String.class, branch -> {
                branch.wait("long", Duration.ofSeconds(8));
                return "long-done";
            }));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }

    private static final class WaitReplayFlagPlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            if (!PluginSupport.isWait(info.type())) {
                return;
            }
            // pending := non-terminal at hook time, from the hook info's own operation state (no end timestamp
            // yet) — no cross-invocation state.
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-start\", \"type\": \"%s\", \"name\": \"%s\", "
                            + "\"replay\": %b, \"pending\": %b%s}",
                    info.type().toUpperCase(Locale.ROOT),
                    info.name(),
                    info.isReplay(),
                    info.endTimestamp() == null,
                    PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isWait(info.type())) {
                return;
            }
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-end\", \"type\": \"%s\", \"name\": \"%s\", "
                            + "\"status\": \"%s\"%s}",
                    info.type().toUpperCase(Locale.ROOT),
                    info.name(),
                    info.status(),
                    PluginSupport.arnField(executionArn)));
        }
    }
}
