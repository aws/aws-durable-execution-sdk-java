// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.time.Instant;
import java.util.Map;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationInfo;

/**
 * Test helper: builds the per-invocation plugin instances the {@link ExportScheduler} schedules for.
 *
 * <p>The scheduler no longer resolves an execution ARN to anything — an invocation's state <em>is</em> its plugin
 * instance, created from the {@link InvocationInfo} the SDK is about to hand the first hook — so tests hold the
 * instance exactly as the SDK does. Everything here goes through the same constructor and the same factory production
 * uses; nothing is a test-only back door into the scheduler.
 */
final class Executions {

    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private Executions() {}

    /** The invocation description the SDK would build for one execution, with no payload or operation snapshot. */
    static InvocationInfo info(String executionArn) {
        return new InvocationInfo("req", executionArn, true, START, null, Map.of(), Map.of());
    }

    /**
     * One invocation's plugin instance, bound to this scheduler and configured with the plugin's defaults. For tests
     * that drive the scheduler directly and do not care how records are shaped.
     */
    static InsightPlugin plugin(ExportScheduler scheduler, String executionArn) {
        return new InsightPlugin(
                new InsightSettings(WorkflowInsightConfig.builder().build()), scheduler, info(executionArn));
    }

    /**
     * One invocation's plugin instance from the factory, for the identity the SDK would have built it with. The
     * invocation's own {@code InvocationInfo} still goes to {@code onInvocationStart}; this is the same pair of facts
     * that info carries, which is all an instance's identity is.
     */
    static InsightPlugin plugin(DurableExecutionPluginFactory factory, String executionArn, Instant startTime) {
        return plugin(factory, new InvocationInfo("req", executionArn, true, startTime, null, Map.of(), Map.of()));
    }

    /**
     * One invocation's plugin instance, exactly as the SDK creates it: from the factory, with that invocation's info.
     */
    static InsightPlugin plugin(DurableExecutionPluginFactory factory, InvocationInfo info) {
        return (InsightPlugin) factory.createPlugin(info);
    }

    /**
     * Whether the scheduler still owes this invocation anything: a queued record, a record inside the exporters, an
     * uncompleted drain signal, or a drain waiting on it. Read under the monitor those fields are guarded by.
     *
     * <p>This is the question the plugin's {@code retainedStateCount()} seam used to answer for a whole registry. There
     * is no registry to count now — an invocation's state is its plugin instance, and the SDK drops it — so the
     * property worth asserting is that nothing the environment outlives keeps hold of it.
     */
    static boolean outstanding(InsightPlugin plugin) {
        synchronized (plugin.scheduler) {
            return plugin.record != null || plugin.exporting || plugin.settled != null || plugin.drainWaiters > 0;
        }
    }

    /**
     * The instance plus its first hook, in the order the SDK dispatches them: the factory is called with the very
     * {@link InvocationInfo} that {@code onInvocationStart} then receives.
     */
    static InsightPlugin started(DurableExecutionPluginFactory factory, InvocationInfo info) {
        InsightPlugin plugin = plugin(factory, info);
        plugin.onInvocationStart(info);
        return plugin;
    }
}
