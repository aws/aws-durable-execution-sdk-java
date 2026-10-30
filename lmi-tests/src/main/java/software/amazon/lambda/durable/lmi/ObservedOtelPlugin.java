// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.MDC;
import software.amazon.lambda.durable.otel.ExecutionOtelPlugin;
import software.amazon.lambda.durable.otel.InvocationOtelPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.HandlerScoped;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.PropagationInput;
import software.amazon.lambda.durable.plugin.PropagationMetadata;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/** Adds only diagnostic ownership; every hook and handler scope reaches the real plugin. */
@HandlerScoped(ObservedOtelPlugin.ScopeOpener.class)
final class ObservedOtelPlugin implements DurableExecutionPlugin {
    static final ThreadLocal<InvocationTrace> EXPORT_OWNER = new ThreadLocal<>();
    private final DurableExecutionPlugin delegate;
    private final InvocationTrace trace;
    private final IntSupplier shutdowns;
    private Context before;
    private Map<String, String> beforeMdc;
    private long ownerThread;

    ObservedOtelPlugin(DurableExecutionPlugin delegate, InvocationTrace trace, IntSupplier shutdowns) {
        this.delegate = delegate;
        this.trace = trace;
        this.shutdowns = shutdowns;
    }

    private <T> T observe(Supplier<T> action) {
        return withOwner(trace, action);
    }

    static <T> T withOwner(InvocationTrace trace, Supplier<T> action) {
        var previous = EXPORT_OWNER.get();
        EXPORT_OWNER.set(trace);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    private void observe(Runnable action) {
        observe(() -> {
            action.run();
            return null;
        });
    }

    private static void restore(InvocationTrace previous) {
        if (previous == null) EXPORT_OWNER.remove();
        else EXPORT_OWNER.set(previous);
    }

    @Override
    public void onInvocationStart(InvocationInfo info) {
        before = Context.current();
        beforeMdc = MDC.getCopyOfContextMap();
        ownerThread = Thread.currentThread().getId();
        trace.event("OTEL_START", Map.of("ownerThread", ownerThread));
        observe(() -> delegate.onInvocationStart(info));
    }

    @Override
    public void onInvocationEnd(InvocationEndInfo info) {
        try {
            observe(() -> delegate.onInvocationEnd(info));
        } finally {
            trace.event(
                    "OTEL_END",
                    Map.of(
                            "contextRestored",
                            Context.current() == before,
                            "mdcRestored",
                            Objects.equals(beforeMdc, MDC.getCopyOfContextMap()),
                            "ownerThread",
                            ownerThread,
                            "endThread",
                            Thread.currentThread().getId(),
                            "status",
                            info.invocationStatus().name(),
                            "exporterShutdowns",
                            shutdowns.getAsInt()));
        }
    }

    @Override
    public void onOperationStart(OperationInfo info) {
        observe(() -> delegate.onOperationStart(info));
    }

    @Override
    public void onOperationEnd(OperationEndInfo info) {
        observe(() -> delegate.onOperationEnd(info));
    }

    @Override
    public void onOperationChange(OperationChangeInfo info) {
        observe(() -> delegate.onOperationChange(info));
    }

    @Override
    public void onUserFunctionStart(UserFunctionStartInfo info) {
        observe(() -> delegate.onUserFunctionStart(info));
    }

    @Override
    public void onUserFunctionEnd(UserFunctionEndInfo info) {
        observe(() -> delegate.onUserFunctionEnd(info));
    }

    @Override
    public PropagationMetadata providePropagationMetadata(PropagationInput info) {
        return observe(() -> delegate.providePropagationMetadata(info));
    }

    @Override
    public String providePropagationMetadata(String arn, String operation, String parent, String target) {
        return observe(() -> delegate.providePropagationMetadata(arn, operation, parent, target));
    }

    private AutoCloseable openScope() {
        var scope = observe(() -> delegate instanceof ExecutionOtelPlugin execution
                ? new ExecutionOtelPlugin.HandlerScopeOpener().apply(execution)
                : new InvocationOtelPlugin.HandlerScopeOpener().apply((InvocationOtelPlugin) delegate));
        if (scope == null) return null;
        return () -> {
            var previous = EXPORT_OWNER.get();
            EXPORT_OWNER.set(trace);
            try {
                scope.close();
            } finally {
                restore(previous);
            }
        };
    }

    public static final class ScopeOpener implements Function<ObservedOtelPlugin, AutoCloseable> {
        public ScopeOpener() {}

        @Override
        public AutoCloseable apply(ObservedOtelPlugin plugin) {
            return plugin.openScope();
        }
    }
}
