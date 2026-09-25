// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationInfo;

/**
 * Test helper: builds one invocation's plugin instance the way the SDK does.
 *
 * <p>A plugin instance serves exactly one invocation, so a test that drives several invocations of an execution creates
 * one instance per invocation from the same factory — the factory being what the environment owns. The factory is
 * called with the very {@link InvocationInfo} that {@code onInvocationStart} then receives, exactly as
 * {@code PluginRunner} does.
 */
final class Invocations {

    private Invocations() {}

    /** One invocation's plugin instance, created from the factory and started with the same info. */
    static DurableExecutionPlugin started(DurableExecutionPluginFactory factory, InvocationInfo info) {
        var plugin = factory.createPlugin(info);
        plugin.onInvocationStart(info);
        return plugin;
    }
}
