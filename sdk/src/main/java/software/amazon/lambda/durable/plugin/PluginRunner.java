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
 * returns {@code null} is contained the same way — the plugin is skipped for the invocation. Containment covers
 * {@link LinkageError} as well as {@link Exception}, because a plugin built against a different SDK version or missing
 * an optional dependency fails with an {@code Error}; it deliberately stops short of the {@code Error}s that report the
 * JVM itself failing.
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
     *
     * <p>{@link LinkageError} is contained alongside {@link Exception} because it is how the two most likely
     * version-skew failures of this contract present themselves, and neither is an {@code Exception}: a provider JAR
     * compiled against an earlier version of {@link DurableExecutionPluginFactory} throws {@link AbstractMethodError}
     * when the SDK invokes the method it does not implement, and a provider whose optional dependency is missing from
     * the deployment package throws {@link NoClassDefFoundError} while building its plugin. The contract says a factory
     * failure is skipped and never disrupts the execution, so both are skipped. Deliberately narrow: an {@code Error}
     * that reports the JVM itself failing — {@link OutOfMemoryError}, {@link StackOverflowError} — is not a plugin
     * defect and must keep propagating rather than be logged as one.
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
            } catch (Exception | LinkageError e) {
                logger.warn("Plugin factory failed; skipping it for this invocation", e);
            }
        }
        this.plugins = List.copyOf(created);
    }

    /**
     * Drops this invocation's plugin instances. Called when the invocation returns so the instances are unreachable
     * from the SDK and cannot leak into the next invocation the environment hosts.
     *
     * <p>No containment here: this only replaces the field, and calls nothing on the plugins it drops. There is no
     * {@code close()} in the plugin contract, so releasing cannot run plugin code and cannot fail.
     */
    public void releasePlugins() {
        this.plugins = List.of();
    }

    // ─── Event hooks ─────────────────────────────────────────────────────

    /**
     * Calls a void hook on all of this invocation's plugins, swallowing any errors.
     *
     * <p>{@link LinkageError} is contained alongside {@link Exception} for the reason given on {@link #createPlugins}:
     * a plugin compiled against a different SDK version, or one missing an optional dependency, fails a hook with an
     * {@code Error} rather than an {@code Exception}, and the fire-and-forget contract makes no distinction. Narrow on
     * purpose — {@link OutOfMemoryError} and {@link StackOverflowError} still propagate.
     */
    private void run(Consumer<DurableExecutionPlugin> hook) {
        for (var plugin : plugins) {
            try {
                hook.accept(plugin);
            } catch (Exception | LinkageError e) {
                logger.warn("Plugin hook failed", e);
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
