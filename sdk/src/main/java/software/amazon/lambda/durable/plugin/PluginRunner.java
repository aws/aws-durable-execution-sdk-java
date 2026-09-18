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
 * returns {@code null} is contained the same way — the plugin is skipped for the invocation. Containment covers every
 * non-fatal throwable, not only {@link Exception}, because a plugin built against a different SDK version, one missing
 * an optional dependency, and one running with assertions enabled all fail with an {@code Error}. It stops short of two
 * cases, which keep propagating: the errors that report the JVM itself failing, and the {@code ThreadDeath} that
 * reports the thread running the plugin has already been terminated.
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
     * <p>Every non-fatal throwable is contained, not just {@link Exception}. The contract says a factory failure is
     * skipped and never disrupts the execution, and a throwable that escapes here fails an execution the plugin was
     * only observing. Narrowing the catch to a list of types would leave that promise conditional on the list being
     * complete, and it was not: a provider JAR compiled against an earlier version of
     * {@link DurableExecutionPluginFactory} throws {@link AbstractMethodError}, a provider whose optional dependency is
     * missing from the deployment package throws {@link NoClassDefFoundError}, a provider running with assertions
     * enabled throws {@link AssertionError}, and a provider that loads its own exporter back ends through
     * {@link java.util.ServiceLoader} throws {@link java.util.ServiceConfigurationError}. Only the first two are
     * {@link LinkageError} and none is an {@link Exception}. Catching {@code Throwable} and rethrowing only the fatal
     * cases makes the promise unconditional. See {@link #contain} for which cases stay fatal.
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
            } catch (Throwable t) {
                contain(t, "Plugin factory failed; skipping it for this invocation");
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
     * Calls a void hook on all of this invocation's plugins, swallowing any non-fatal throwable.
     *
     * <p>Containment here follows the same rule as {@link #createPlugins}, because the fire-and-forget contract makes
     * no distinction between the two boundaries. A plugin fails a hook with the same shapes a factory fails with, and
     * one plugin's failure must not stop the remaining plugins from receiving the hook or fail the execution. See
     * {@link #contain} for which cases stay fatal.
     */
    private void run(Consumer<DurableExecutionPlugin> hook) {
        for (var plugin : plugins) {
            try {
                hook.accept(plugin);
            } catch (Throwable t) {
                contain(t, "Plugin hook failed");
            }
        }
    }

    /**
     * Logs a throwable that plugin code produced, or rethrows it if it is fatal.
     *
     * <p>A {@link VirtualMachineError} is the JVM reporting that it can no longer run correctly, which covers
     * {@link OutOfMemoryError}, {@link StackOverflowError}, {@link InternalError} and {@link UnknownError}. That is not
     * a plugin defect, and the process cannot be assumed able to continue past it. Logging it as a contained plugin
     * failure would therefore hide a condition the caller has to see, so it is rethrown unchanged. The rule names the
     * supertype rather than the four subclasses so that a subclass added later is fatal without an edit here.
     *
     * <p>{@code ThreadDeath} is fatal for a different reason. It is not a report of a failure but a thread termination
     * that has already begun: {@code Thread.stop()} delivers it by throwing it into the target thread, which unwinds
     * that thread's stack from wherever it stood and releases the monitors it held over state it had only half updated.
     * The threads that create plugins and fire hooks are SDK threads that carry SDK and user work after the plugin
     * returns. Containing the {@code ThreadDeath} would therefore return one of those threads to that work with its
     * invariants already broken and the termination it was sent silently dropped. It is rethrown unchanged so the
     * termination completes.
     *
     * <p>{@code Thread.stop()} throws {@link UnsupportedOperationException} on JDK 20 and later, so the JVM cannot
     * deliver a {@code ThreadDeath} on those runtimes. It can deliver one on JDK 17, and {@code maven.compiler.source}
     * is 17, so the rethrow is reachable on a runtime this SDK supports. A {@code ThreadDeath} that plugin code
     * constructs and throws itself is rethrown on every runtime; the boundary cannot distinguish it from a delivered
     * one, and treating the ambiguous case as fatal is the safe direction.
     *
     * <p>{@code ThreadDeath} is deprecated for removal since JDK 20, so naming it emits a removal warning when this
     * class is compiled on a JDK 20 or later compiler. The {@code @SuppressWarnings("removal")} below is scoped to this
     * method rather than the class so it cannot mask a removal warning that appears elsewhere in {@code PluginRunner}.
     *
     * <p>Throwing an {@link InterruptedException} clears the throwing thread's interrupt status. The threads that run
     * factories and hooks are SDK threads that carry SDK work after the plugin returns, so containing the interrupt
     * without restoring the status would hide the cancellation request from that later work and from the SDK's own
     * blocking calls. The status is therefore restored before returning. Restoring it immediately rather than after the
     * dispatch loop keeps the flag true for every subsequent read on this thread; a remaining plugin whose blocking
     * call then fails fast is contained by this same rule, so every plugin is still called.
     */
    @SuppressWarnings("removal") // ThreadDeath is deprecated for removal since JDK 20; see the javadoc above.
    private static void contain(Throwable t, String message) {
        if (t instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (t instanceof ThreadDeath fatal) {
            throw fatal;
        }
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        logger.warn(message, t);
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
