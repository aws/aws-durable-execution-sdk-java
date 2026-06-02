// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes multiple {@link DurableExecutionPlugin} instances into a single dispatcher.
 *
 * <p>Event hooks are fire-and-forget: each plugin is called in order, errors are swallowed.
 *
 * <p>{@code onInvocationEnd} is awaited (the SDK blocks until it returns) to allow plugins to flush data before Lambda
 * freezes.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public class PluginRunner {

    private static final Logger logger = LoggerFactory.getLogger(PluginRunner.class);
    private static final PluginRunner NO_OP = new PluginRunner(Collections.emptyList());

    private final List<DurableExecutionPlugin> plugins;

    public PluginRunner(List<DurableExecutionPlugin> plugins) {
        this.plugins = plugins != null ? List.copyOf(plugins) : Collections.emptyList();
    }

    /** Returns a no-op runner that does nothing. */
    public static PluginRunner noOp() {
        return NO_OP;
    }

    /** Returns true if no plugins are registered. */
    public boolean isEmpty() {
        return plugins.isEmpty();
    }

    /** Returns the list of registered plugins. */
    public List<DurableExecutionPlugin> getPlugins() {
        return plugins;
    }

    // ─── Event hooks ─────────────────────────────────────────────────────

    /** Calls a void hook on all plugins, swallowing any errors. */
    private void run(Consumer<DurableExecutionPlugin> hook) {
        for (var plugin : plugins) {
            try {
                hook.accept(plugin);
            } catch (Exception e) {
                logger.warn("Plugin hook threw exception", e);
            }
        }
    }

    public void onInvocationStart(InvocationInfo info) {
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

    public void onUserFunctionStart(UserFunctionStartInfo info) {
        run(p -> p.onUserFunctionStart(info));
    }

    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        run(p -> p.onUserFunctionEnd(info));
    }
}
