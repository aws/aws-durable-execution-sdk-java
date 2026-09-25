// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/**
 * Creates one {@link DurableExecutionPlugin} instance per Lambda invocation.
 *
 * <p>The SDK builds the {@link InvocationInfo} for an invocation, calls this factory with it, dispatches that
 * invocation's hooks to the returned instance, and drops the instance when the invocation returns. A plugin instance
 * therefore serves exactly one invocation and can hold per-invocation state in plain fields — no keying by execution
 * ARN is needed, even when the execution environment runs several executions concurrently.
 *
 * <p>The {@link InvocationInfo} handed to the factory is the same instance the plugin's
 * {@link DurableExecutionPlugin#onInvocationStart(InvocationInfo)} hook then receives.
 *
 * <p>Factory failures are contained exactly like hook failures: a factory that throws or returns {@code null} is logged
 * and skipped for that invocation, and never disrupts the execution.
 *
 * <pre>{@code
 * DurableConfig.builder()
 *     .withPlugins(info -> new MyPlugin(info.durableExecutionArn()))
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface DurableExecutionPluginFactory {

    /**
     * Creates the plugin instance that serves the described invocation.
     *
     * @param invocationInfo the invocation the plugin instance will observe
     * @return the plugin instance for this invocation
     */
    DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo);
}
