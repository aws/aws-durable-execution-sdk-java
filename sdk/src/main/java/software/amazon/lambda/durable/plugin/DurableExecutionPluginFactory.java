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
 * <p>Non-fatal factory failures are contained like non-fatal hook failures: the factory is logged and skipped for that
 * invocation. A {@code null} result is also logged and skipped. {@link VirtualMachineError} and {@link ThreadDeath}
 * propagate, including when wrapped by asynchronous completion/future exceptions.
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
    /** Creates an instance using an immutable header captured on the Lambda runtime thread. */
    default DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo, String runtimeTraceHeader) {
        return createPlugin(invocationInfo);
    }
    /** Optional exclusive instrumentation group, validated before any invocation instance is created. */
    default String getExclusiveGroup() {
        return null;
    }
}
