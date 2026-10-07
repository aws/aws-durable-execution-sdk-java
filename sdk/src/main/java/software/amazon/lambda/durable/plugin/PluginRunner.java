// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Composes multiple {@link DurableExecutionPlugin} instances into a single dispatcher.
 *
 * <p>Event hooks call each plugin in order. Exceptions and nonfatal linkage failures are isolated; other errors,
 * including fatal JVM failures, retain their existing propagation behavior.
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
        for (var plugin : plugins) {
            try {
                hook.accept(plugin);
            } catch (Exception e) {
                logger.warn("Plugin hook threw exception", e);
            } catch (LinkageError e) {
                logger.warn("Plugin hook could not link a dependency; check SDK/plugin dependency compatibility", e);
            }
        }
    }

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

    private static void reportHandlerScopeFailure(String message, Throwable failure, Consumer<Error> onScopeFatal) {
        reportHandlerScopeFatal(failure, onScopeFatal);
        try {
            // A plugin-controlled diagnostic must not be inspected again by the logger.
            logger.warn("{} ({})", message, failure.getClass().getName());
        } catch (Throwable loggingFailure) {
            reportHandlerScopeFatal(loggingFailure, onScopeFatal);
        }
    }

    private static void reportHandlerScopeFatal(Throwable failure, Consumer<Error> onScopeFatal) {
        var fatal = findHandlerScopeFatal(failure);
        if (fatal != null) {
            // The caller must wake even when the fatal originated in an exception's cause accessor.
            onScopeFatal.accept(fatal);
            throw fatal;
        }
    }

    @SuppressWarnings("removal")
    private static Error findHandlerScopeFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) return (Error) failure;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var cause = failure;
        while (seen.add(cause)) {
            if (cause instanceof VirtualMachineError || cause instanceof ThreadDeath) return (Error) cause;
            if (!(cause instanceof CompletionException
                    || cause instanceof ExecutionException
                    || cause instanceof InvocationTargetException
                    || cause instanceof UndeclaredThrowableException)) return null;
            try {
                cause = cause.getCause();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                return fatal;
            } catch (Throwable unreadableCause) {
                return null;
            }
            if (cause == null) return null;
        }
        return null;
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
