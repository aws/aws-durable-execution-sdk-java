// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches the lifecycle hooks of a single Lambda invocation to that invocation's plugin instances.
 *
 * <p>A runner is created per invocation from the configured {@link DurableExecutionPluginFactory factories} and holds
 * no plugin instances until {@link #onInvocationStart(InvocationInfo)} materializes them — once, before the first hook
 * fires, from the very {@link InvocationInfo} the first hook then receives. {@link #releasePlugins()} drops them when
 * the invocation returns, so a plugin instance is never shared between invocations and never needs to key its state by
 * execution ARN.
 *
 * <p>Event hooks are fire-and-forget: each plugin is called in order, errors are swallowed. A factory that throws or
 * returns {@code null} is contained the same way — the plugin is skipped for the invocation.
 *
 * <p>{@code onInvocationEnd} is awaited (the SDK blocks until it returns) to allow plugins to flush data before Lambda
 * freezes.
 */
public class PluginRunner {

    private static final Logger logger = LoggerFactory.getLogger(PluginRunner.class);

    private final List<DurableExecutionPluginFactory> pluginFactories;

    /**
     * This invocation's plugin instances. Written once on the thread that fires {@code onInvocationStart}, read from
     * the user, checkpoint, and operation threads that fire the later hooks — volatile for that publication.
     */
    private volatile List<DurableExecutionPlugin> plugins = List.of();

    public PluginRunner(List<DurableExecutionPluginFactory> pluginFactories) {
        this.pluginFactories = pluginFactories != null ? List.copyOf(pluginFactories) : Collections.emptyList();
    }

    /** Returns a runner with no plugin factories, which does nothing. */
    public static PluginRunner noOp() {
        return new PluginRunner(Collections.emptyList());
    }

    /** Returns true if no plugin factories are registered. */
    public boolean isEmpty() {
        return pluginFactories.isEmpty();
    }

    // ─── Per-invocation lifetime ─────────────────────────────────────────

    /**
     * Creates this invocation's plugin instances, one per registered factory.
     *
     * <p>Called from {@link #onInvocationStart(InvocationInfo)} so the instances exist before any hook is dispatched.
     * Factories that throw or return null are logged and skipped.
     */
    private void createPlugins(InvocationInfo info) {
        var created = new ArrayList<DurableExecutionPlugin>(pluginFactories.size());
        for (var factory : pluginFactories) {
            try {
                var plugin = factory.createPlugin(info);
                if (plugin == null) {
                    logger.warn("Plugin factory {} returned null; skipping it for this invocation", factory);
                    continue;
                }
                created.add(plugin);
            } catch (Exception e) {
                logger.warn("Plugin factory threw exception; skipping it for this invocation", e);
            }
        }
        this.plugins = List.copyOf(created);
    }

    /**
     * Drops this invocation's plugin instances. Called when the invocation returns so the instances are unreachable
     * from the SDK and cannot leak into the next invocation the environment hosts.
     */
    public void releasePlugins() {
        this.plugins = List.of();
    }

    // ─── Event hooks ─────────────────────────────────────────────────────

    /** Calls a void hook on all of this invocation's plugins, swallowing any errors. */
    private void run(Consumer<DurableExecutionPlugin> hook) {
        for (var plugin : plugins) {
            try {
                hook.accept(plugin);
            } catch (Exception e) {
                logger.warn("Plugin hook threw exception", e);
            }
        }
    }

    /**
     * Called at the start of each invocation. Materializes this invocation's plugin instances from the registered
     * factories, then dispatches the hook to them with the same {@link InvocationInfo} the factories received.
     */
    public void onInvocationStart(InvocationInfo info) {
        createPlugins(info);
        run(p -> p.onInvocationStart(info));
    }

    /**
     * Called at the end of each invocation. Awaited — the SDK blocks until all plugins return, allowing plugins to
     * flush spans/metrics before Lambda freezes.
     */
    public void onInvocationEnd(InvocationEndInfo info) {
        run(p -> p.onInvocationEnd(info));
    }

    public void onOperationStart(OperationInfo info) {
        run(p -> p.onOperationStart(info));
    }

    public void onOperationEnd(OperationEndInfo info) {
        run(p -> p.onOperationEnd(info));
    }

    public void onOperationChange(OperationChangeInfo info) {
        run(p -> p.onOperationChange(info));
    }

    public void onUserFunctionStart(UserFunctionStartInfo info) {
        run(p -> p.onUserFunctionStart(info));
    }

    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        run(p -> p.onUserFunctionEnd(info));
    }
}
