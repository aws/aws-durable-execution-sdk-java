// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes multiple {@link DurableExecutionPlugin} instances into a single dispatcher.
 *
 * <p>Event hooks call each plugin in registration order, except invocation end, which unwinds in reverse order.
 * Exceptions and nonfatal linkage failures are isolated; other errors propagate. Invocation end finishes the remaining
 * cleanup hooks before propagating the first error.
 *
 * <p>{@code onInvocationEnd} is awaited (the SDK blocks until it returns) to allow plugins to flush data before Lambda
 * freezes.
 */
public class PluginRunner {

    private static final Logger logger = LoggerFactory.getLogger(PluginRunner.class);
    private static final PluginRunner NO_OP = new PluginRunner(Collections.emptyList());

    private final List<DurableExecutionPlugin> plugins;

    public PluginRunner(List<DurableExecutionPlugin> plugins) {
        this.plugins = plugins != null ? List.copyOf(plugins) : Collections.emptyList();
        validateExclusiveGroups();
    }

    private void validateExclusiveGroups() {
        var groups = new HashMap<String, DurableExecutionPlugin>();
        for (var plugin : plugins) {
            for (var group : exclusiveGroups(plugin.getClass())) {
                var previous = groups.putIfAbsent(group, plugin);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Dynamic plugin configuration failed: Conflicting plugins " + pluginName(previous)
                                    + " and " + pluginName(plugin) + " in exclusive group '" + group
                                    + "'. Configure only one plugin from this group.");
                }
            }
        }
    }

    private static String pluginName(DurableExecutionPlugin plugin) {
        var type = plugin.getClass();
        return type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName();
    }

    private static Set<String> exclusiveGroups(Class<?> pluginType) {
        var groups = new LinkedHashSet<String>();
        for (var type = pluginType; type != null; type = type.getSuperclass()) {
            var metadata = type.getDeclaredAnnotation(ExclusivePluginGroup.class);
            if (metadata != null) {
                groups.add(metadata.value());
            }
        }
        return groups;
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

    /** Calls a void hook on all plugins, isolating exceptions and incompatible binary dependencies. */
    private void run(Consumer<DurableExecutionPlugin> hook) {
        for (var plugin : plugins) runHook(plugin, hook);
    }

    private void runHook(DurableExecutionPlugin plugin, Consumer<DurableExecutionPlugin> hook) {
        try {
            hook.accept(plugin);
        } catch (Exception e) {
            logger.warn("Plugin hook threw exception", e);
        } catch (LinkageError e) {
            logger.warn("Plugin hook could not link a dependency; check SDK/plugin dependency compatibility", e);
        }
    }

    public void onInvocationStart(InvocationInfo info) {
        run(p -> p.onInvocationStart(info));
    }

    /**
     * Called in reverse registration order on the root handler thread after it unwinds. Awaited — the SDK blocks until
     * all plugins return, including during suspension or termination, allowing plugins to flush spans/metrics before
     * Lambda freezes.
     */
    public void onInvocationEnd(InvocationEndInfo info) {
        Error firstError = null;
        for (var index = plugins.size() - 1; index >= 0; index--) {
            try {
                runHook(plugins.get(index), p -> p.onInvocationEnd(info));
            } catch (Error failure) {
                // Finish unwinding earlier plugins' thread-local scopes before propagating an end-hook error.
                if (firstError == null) {
                    firstError = failure;
                } else if (firstError != failure) {
                    if (isJvmFatal(failure) && !isJvmFatal(firstError)) {
                        failure.addSuppressed(firstError);
                        firstError = failure;
                    } else {
                        firstError.addSuppressed(failure);
                    }
                }
            }
        }
        if (firstError != null) throw firstError;
    }

    @SuppressWarnings("removal")
    private static boolean isJvmFatal(Error failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
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
