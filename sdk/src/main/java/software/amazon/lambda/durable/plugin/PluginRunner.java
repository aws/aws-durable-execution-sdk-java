// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
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

    @SuppressWarnings("removal")
    private static void reportHandlerScopeFailure(String message, Throwable failure, Consumer<Error> onScopeFatal) {
        var cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof VirtualMachineError || cause instanceof ThreadDeath) {
            var fatal = (Error) cause;
            onScopeFatal.accept(fatal);
            throw fatal;
        }
        logger.warn(message, failure);
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
