// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static java.util.Objects.requireNonNull;

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
 */
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

    /**
     * Collects supported metadata in configured order on the caller's thread. First non-null member wins; matching
     * later values are harmless. Ordinary plugin/invalid-result failures are logged and skipped, matching the event
     * hooks' Exception containment policy; Errors continue to propagate. The core calls this while creating a new
     * invoke START checkpoint, not while replaying an existing operation.
     */
    public PropagationMetadata providePropagationMetadata(PropagationInput input) {
        requireNonNull(input, "input");
        String selected = null;
        String owner = null;
        var conflicts = 0;
        for (var index = 0; index < plugins.size(); index++) {
            var plugin = plugins.get(index);
            var identity = plugin.getClass().getName() + "[" + index + "]";
            try {
                var candidate = propagationHeader(plugin, input);
                if (candidate == null) continue;
                if (selected == null) {
                    selected = candidate;
                    owner = identity;
                } else if (!selected.equals(candidate)) {
                    warnPropagation(
                            "Conflicting xAmznTraceId from plugin {}; retaining plugin {} (conflict count: {})",
                            identity,
                            owner,
                            ++conflicts);
                }
            } catch (Exception failure) {
                warnPropagation("Propagation metadata from plugin {} failed; skipping contribution", identity, failure);
            }
        }
        return selected != null ? new PropagationMetadata(selected) : null;
    }

    private static String propagationHeader(DurableExecutionPlugin plugin, PropagationInput input) {
        var metadata = plugin.providePropagationMetadata(input);
        var header = metadata != null ? metadata.xAmznTraceId() : null;
        if (header != null && header.isBlank()) {
            throw new IllegalArgumentException("xAmznTraceId must not be blank");
        }
        return header;
    }

    private static void warnPropagation(String message, Object... arguments) {
        try {
            logger.warn(message, arguments);
        } catch (RuntimeException ignored) {
            // A failed logging backend cannot change optional metadata collection or the handler's result.
        }
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
