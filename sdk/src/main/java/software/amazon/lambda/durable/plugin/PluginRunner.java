// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Dispatches the lifecycle hooks of a single Lambda invocation to that invocation's plugin instances.
 *
 * <p>A runner is created per invocation from the configured {@link DurableExecutionPluginFactory factories} and holds
 * no plugin instances until {@link #onInvocationStart(InvocationInfo)} materializes and starts each plugin in
 * configured order, from the very {@link InvocationInfo} the first hook then receives. {@link #releasePlugins()} drops
 * them when the invocation returns, so a plugin instance is never shared between invocations and never needs to key its
 * state by execution ARN.
 *
 * <p>Event hooks are fire-and-forget: each plugin is called in order, non-fatal failures are contained. A factory with
 * a non-fatal failure or returns {@code null} is contained the same way — the plugin is skipped for the invocation.
 * Containment covers every non-fatal throwable, not only {@link Exception}, because a plugin built against a different
 * SDK version, one missing an optional dependency, and one running with assertions enabled all fail with an
 * {@code Error}. It stops short of two cases, which keep propagating: the errors that report the JVM itself failing,
 * and the {@code ThreadDeath} that reports the thread running the plugin has already been terminated.
 *
 * <p>{@code onInvocationEnd} is awaited (the SDK blocks until it returns) to allow plugins to flush data before Lambda
 * freezes.
 */
public class PluginRunner {

    private static final Logger logger = LoggerFactory.getLogger(PluginRunner.class);

    private final List<DurableExecutionPluginFactory> pluginFactories;
    private final Consumer<Error> operationFatalObserver;

    /**
     * This invocation's plugin instances. Written once on the thread that fires {@code onInvocationStart}, read from
     * the user, checkpoint, and operation threads that fire the later hooks — volatile for that publication.
     */
    private volatile List<DurableExecutionPlugin> plugins = List.of();

    public PluginRunner(List<DurableExecutionPluginFactory> pluginFactories) {
        this(pluginFactories, fatal -> {});
    }

    /** Reports fatal operation and invocation-end hooks to their invocation before rethrowing. */
    public PluginRunner(List<DurableExecutionPluginFactory> pluginFactories, Consumer<Error> operationFatalObserver) {
        this.operationFatalObserver = operationFatalObserver;
        this.pluginFactories = pluginFactories != null ? List.copyOf(pluginFactories) : Collections.emptyList();
        validateExclusiveGroups(this.pluginFactories);
    }

    /** Validates configured factory metadata without creating invocation-owned plugins or spans. */
    public static void validateExclusiveGroups(List<DurableExecutionPluginFactory> factories) {
        var groups = new HashMap<String, DurableExecutionPluginFactory>();
        for (var factory : factories) {
            var group = factory.getExclusiveGroup();
            if (group == null) continue;
            var previous = groups.putIfAbsent(group, factory);
            if (previous != null)
                throw new IllegalArgumentException("Conflicting plugin factories " + previous + " and " + factory
                        + " in exclusive group '" + group + "'. Configure only one plugin from this group.");
        }
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
     * <p>Called from {@link #onInvocationStart(InvocationInfo)}. Each start hook runs before constructing the next
     * plugin, preserving startup context installed by earlier registrations. Factories that fail non-fatally or return
     * null are logged and skipped. Fatal causes propagate, including through completion/future wrappers.
     *
     * <p>Every non-fatal throwable is contained, not just {@link Exception}. The contract says a non-fatal factory
     * failure is skipped and never disrupts the execution, and a throwable that escapes here fails an execution the
     * plugin was only observing. Narrowing the catch to a list of types would leave that promise conditional on the
     * list being complete, and it was not: a provider JAR compiled against an earlier version of
     * {@link DurableExecutionPluginFactory} throws {@link AbstractMethodError}, a provider whose optional dependency is
     * missing from the deployment package throws {@link NoClassDefFoundError}, a provider running with assertions
     * enabled throws {@link AssertionError}, and a provider that loads its own exporter back ends through
     * {@link java.util.ServiceLoader} throws {@link java.util.ServiceConfigurationError}. Only the first two are
     * {@link LinkageError} and none is an {@link Exception}. Catching {@code Throwable} and rethrowing only the fatal
     * cases makes the promise unconditional. See {@link #contain} for which cases stay fatal.
     */
    private void createPlugins(InvocationInfo info, String runtimeTraceHeader) {
        var created = new ArrayList<DurableExecutionPlugin>(pluginFactories.size());
        try {
            for (var factory : pluginFactories) {
                var plugin = createPlugin(factory, info, runtimeTraceHeader);
                if (plugin == null) continue;
                created.add(plugin);
                runPlugin(plugin, p -> p.onInvocationStart(info, runtimeTraceHeader));
            }
        } finally {
            // Even a fatal constructor/start failure must leave already-created instances available for finalization.
            // Publishing after startup also makes state assigned by start hooks visible to later SDK threads.
            this.plugins = List.copyOf(created);
        }
    }

    private static DurableExecutionPlugin createPlugin(
            DurableExecutionPluginFactory factory, InvocationInfo info, String runtimeTraceHeader) {
        try {
            var plugin = factory.createPlugin(info, runtimeTraceHeader);
            if (plugin == null)
                logger.warn("Plugin factory {} returned null; skipping it for this invocation", factory);
            return plugin;
        } catch (Throwable failure) {
            contain(failure, "Plugin factory failed; skipping it for this invocation");
            return null;
        }
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
            runPlugin(plugin, hook);
        }
    }

    private static void runPlugin(DurableExecutionPlugin plugin, Consumer<DurableExecutionPlugin> hook) {
        try {
            hook.accept(plugin);
        } catch (Throwable failure) {
            contain(failure, "Plugin hook failed");
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
     * <p>An {@link InterruptedException} is contained like any other non-fatal throwable, and the interrupt status is
     * not restored. Three facts decide it. The thread that creates plugins and fires {@code onInvocationStart} is the
     * handler thread — the hook runs there on purpose, so a plugin can set a {@code ThreadLocal} or an MDC key the
     * handler's own logging then reads — so setting the flag there leaves the handler's next blocking call to fail with
     * an {@code InterruptedException} that no user code asked for, which is the containment contract broken by the
     * boundary meant to enforce it. A thrown {@code InterruptedException} is also no proof that the thread was
     * interrupted: no hook and no factory method declares a checked exception, so the only way one arrives is plugin
     * code rethrowing it undeclared, and plugin code can construct one and throw it with the interrupt status clear.
     * And no SDK code interrupts these threads or reads their interrupt status, so restoring the flag serves no waiting
     * reader. The interrupt is reported the way every other contained plugin failure is, as a logged warning naming the
     * plugin boundary that produced it.
     */
    @SuppressWarnings("removal") // ThreadDeath is deprecated for removal since JDK 20; see the javadoc above.
    private static void contain(Throwable t, String message) {
        var cause = ExceptionHelper.unwrapAsyncFailure(t);
        if (cause instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (cause instanceof ThreadDeath fatal) {
            throw fatal;
        }
        logger.warn(message, t);
    }

    /**
     * Called at the start of each invocation. Materializes this invocation's plugin instances from the registered
     * factories, then dispatches the hook to them with the same {@link InvocationInfo} the factories received.
     */
    /** Runs the root handler with optional plugin scopes, closing them on the same thread in reverse order. */
    public <T> T runHandler(Supplier<T> handler) {
        return runHandler(handler, () -> {});
    }

    /** Runs the handler and notifies the invocation when a scope requires same-thread finalization. */
    public <T> T runHandler(Supplier<T> handler, Runnable onScopeOpened) {
        return runHandler(handler, onScopeOpened, fatal -> {});
    }

    /** Reports fatal errors originating only in scope callbacks, before rethrowing on the owner thread. */
    @SuppressWarnings("removal")
    public <T> T runHandler(Supplier<T> handler, Runnable onScopeOpened, Consumer<Error> onScopeFatal) {
        var scopes = new ArrayDeque<AutoCloseable>();
        try {
            for (var plugin : plugins) {
                try {
                    var scope = openHandlerScope(plugin);
                    if (scope != null) {
                        scopes.push(scope);
                        onScopeOpened.run();
                    }
                } catch (Throwable e) {
                    reportHandlerScopeFailure("Plugin handler scope threw exception", e, onScopeFatal);
                }
            }
            return handler.get();
        } finally {
            closeHandlerScopes(scopes, onScopeFatal);
        }
    }

    @SuppressWarnings("unchecked")
    private static AutoCloseable openHandlerScope(DurableExecutionPlugin plugin) throws ReflectiveOperationException {
        var metadata = plugin.getClass().getAnnotation(HandlerScoped.class);
        if (metadata == null) return null;
        var constructor = metadata.value().getConstructor();
        if (!constructor.canAccess(null) && !constructor.trySetAccessible()) {
            throw new IllegalAccessException(
                    "Cannot access @HandlerScoped opener " + metadata.value().getName());
        }
        try {
            var opener = (Function<Object, AutoCloseable>) constructor.newInstance();
            return opener.apply(plugin);
        } catch (InvocationTargetException failure) {
            ExceptionHelper.sneakyThrow(failure.getCause());
            return null;
        }
    }

    @SuppressWarnings("removal")
    private static void closeHandlerScopes(ArrayDeque<AutoCloseable> scopes, Consumer<Error> onScopeFatal) {
        Error firstFatal = null;
        while (!scopes.isEmpty()) {
            try {
                scopes.pop().close();
            } catch (Throwable e) {
                try {
                    reportHandlerScopeFailure("Plugin handler scope cleanup threw exception", e, onScopeFatal);
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    if (firstFatal == null) firstFatal = fatal;
                }
            }
        }
        if (firstFatal != null) throw firstFatal;
    }

    @SuppressWarnings("removal")
    private static void reportHandlerScopeFailure(String message, Throwable failure, Consumer<Error> onScopeFatal) {
        var cause = ExceptionHelper.unwrapAsyncFailure(failure);
        if (cause instanceof VirtualMachineError || cause instanceof ThreadDeath) {
            var fatal = (Error) cause;
            onScopeFatal.accept(fatal);
            throw fatal;
        }
        logger.warn(message, failure);
    }

    public void onInvocationStart(InvocationInfo info) {
        createPlugins(info, null);
    }

    /** Dispatches the invocation snapshot while preserving legacy hooks through default-method delegation. */
    public void onInvocationStart(InvocationInfo info, String xRayTraceId) {
        createPlugins(info, xRayTraceId);
    }

    /**
     * Called at the end of each invocation. Awaited — the SDK blocks until all plugins return, allowing plugins to
     * flush spans/metrics before Lambda freezes. The first fatal immediately stops outstanding operation work; an
     * earlier invocation fatal retains precedence. Remaining plugins still receive their one finalization opportunity
     * with the shared snapshot before the first end-hook fatal is rethrown.
     */
    @SuppressWarnings("removal")
    public void onInvocationEnd(InvocationEndInfo info) {
        Error firstFatal = null;
        for (var plugin : plugins) {
            try {
                runPlugin(plugin, p -> p.onInvocationEnd(info));
            } catch (VirtualMachineError | ThreadDeath fatal) {
                if (firstFatal == null) {
                    firstFatal = fatal;
                    // Stop queued work before a later exporter can block, but still finalize every plugin below.
                    var primary = ExceptionHelper.unwrapAsyncFailure(info.executionError());
                    operationFatalObserver.accept(
                            primary instanceof VirtualMachineError || primary instanceof ThreadDeath
                                    ? (Error) primary
                                    : firstFatal);
                }
            }
        }
        if (firstFatal != null) throw firstFatal;
    }

    @SuppressWarnings("removal")
    private void runOperationHook(Consumer<DurableExecutionPlugin> hook) {
        try {
            run(hook);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            operationFatalObserver.accept(fatal);
            throw fatal;
        }
    }

    public void onOperationStart(OperationInfo info) {
        runOperationHook(p -> p.onOperationStart(info));
    }

    public void onOperationEnd(OperationEndInfo info) {
        runOperationHook(p -> p.onOperationEnd(info));
    }

    public void onOperationChange(OperationChangeInfo info) {
        runOperationHook(p -> p.onOperationChange(info));
    }

    @SuppressWarnings("removal")
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        var started = new ArrayDeque<DurableExecutionPlugin>();
        try {
            run(plugin -> {
                plugin.onUserFunctionStart(info);
                started.push(plugin);
            });
        } catch (VirtualMachineError | ThreadDeath fatal) {
            // Unwind attempt scopes on their owner before publishing the fatal: publication can start invocation
            // finalization on another thread, where it is too late to restore these thread-local scopes safely.
            closeStartedUserFunctions(started, info, fatal);
            operationFatalObserver.accept(fatal);
            throw fatal;
        }
    }

    @SuppressWarnings("removal")
    private static void closeStartedUserFunctions(
            ArrayDeque<DurableExecutionPlugin> started, UserFunctionStartInfo info, Error fatal) {
        var endInfo = PluginInfoConverter.toUserFunctionEndInfo(info, UserFunctionOutcome.FAILED, fatal);
        while (!started.isEmpty()) {
            try {
                runPlugin(started.pop(), plugin -> plugin.onUserFunctionEnd(endInfo));
            } catch (VirtualMachineError | ThreadDeath cleanupFailure) {
                // Each earlier start gets its cleanup opportunity; preserve the original start-hook failure.
                if (cleanupFailure != fatal) fatal.addSuppressed(cleanupFailure);
            }
        }
    }

    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        runOperationHook(p -> p.onUserFunctionEnd(info));
    }
}
