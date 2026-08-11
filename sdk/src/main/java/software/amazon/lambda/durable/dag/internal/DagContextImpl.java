// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableContext.MapFunction;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.dag.DagCallbackSubmitter;
import software.amazon.lambda.durable.dag.DagChildFunction;
import software.amazon.lambda.durable.dag.DagConditionFunction;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagContext;
import software.amazon.lambda.durable.dag.DagPayloadFunction;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.DagStep1Function;
import software.amazon.lambda.durable.dag.DagStep2Function;
import software.amazon.lambda.durable.dag.DagStep3Function;
import software.amazon.lambda.durable.dag.DagStepFunction;
import software.amazon.lambda.durable.dag.Deps;
import software.amazon.lambda.durable.dag.TaskHandle;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.extension.ExtensionInvokeConfig;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.operation.DurableMapOperation;
import software.amazon.lambda.durable.operation.DurableParallelOperation;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation;
import software.amazon.lambda.durable.retry.RetryStrategy;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Concrete {@link DagContext}. Each registration method records a {@link TaskHandleImpl} whose executor launches the
 * underlying operation through a stable public extension-operation reservation.
 */
public final class DagContextImpl implements DagContext {
    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;
    private static final String CALLBACK_SUBTYPE = "Callback";
    private static final String DAG_SUBTYPE = "Dag";
    private static final String INVOKE_SUBTYPE = "ChainedInvoke";
    private static final String RUN_IN_CHILD_CONTEXT_SUBTYPE = "RunInChildContext";
    private static final String STEP_SUBTYPE = "Step";
    private static final String WAIT_SUBTYPE = "Wait";

    private final List<TaskHandleImpl<?>> tasks = new ArrayList<>();

    /**
     * Declared result types of this scope's tasks, keyed by task name — the graph that lets {@link DagResultSerDes}
     * recover a PLAIN result's type on replay from the registered task (by name) instead of from a class name persisted
     * in the checkpoint. Populated during registration; nested DAGs contribute their own graph recursively (see
     * {@link #collectResultTypes}).
     */
    private final Map<String, TypeToken<?>> declaredResultTypes = new LinkedHashMap<>();

    /** Nested DAG contexts registered in this scope, keyed by their task name, for recursive type-graph assembly. */
    private final Map<String, DagContextImpl> nestedContexts = new LinkedHashMap<>();

    public List<TaskHandleImpl<?>> tasks() {
        return tasks;
    }

    /** Records a task's declared result type by name for PLAIN-result type recovery on replay. Generics welcome. */
    private void recordResultType(String name, TypeToken<?> type) {
        declaredResultTypes.put(name, type);
    }

    /**
     * The transitive declared-result-type graph for this DAG and all nested DAGs, keyed by task name. Handed to
     * {@link DagResultSerDes} so checkpoint replay can reconstruct a PLAIN result into the type the task was declared
     * with — recovered by name from the registered graph, never from an untrusted checkpoint-stored class name.
     */
    public DagResultTypes collectResultTypes() {
        Map<String, DagResultTypes> nested = new LinkedHashMap<>();
        for (var e : nestedContexts.entrySet()) {
            nested.put(e.getKey(), e.getValue().collectResultTypes());
        }
        return new DagResultTypes(declaredResultTypes, nested);
    }

    private <T> TaskHandle<T> register(TaskHandleImpl<T> handle) {
        tasks.add(handle);
        return handle;
    }

    /**
     * Runs the declarative registration phase and validates the resulting graph, returning the populated context.
     *
     * <p>Registration only <em>declares</em> tasks (it launches nothing) and validation is pure graph analysis, so both
     * are deterministic and run <b>eagerly at the {@code dag(...)} call site</b> — not inside the child-context body.
     * This preserves the API contract that graph validation errors are raised directly from the {@code dag(...)} call
     * before any extension operation is reserved or launched.
     */
    public static DagContextImpl registerAndValidate(Consumer<DagContext> register) {
        var dctx = new DagContextImpl();
        register.accept(dctx);
        DagValidator.validate(dctx.tasks());
        return dctx;
    }

    /** Resolves the SerDes used to (de)serialize a DAG's aggregate result. */
    public static SerDes dagSerDes(DagConfig config, SerDes defaultSerDes, DagContextImpl dctx) {
        return config.serDes().orElseGet(() -> new DagResultSerDes(defaultSerDes, dctx.collectResultTypes()));
    }

    /** Starts a registered DAG using a caller-provided extension-operation reservation. */
    public static DurableFuture<DagResult> start(
            ExtensionContext context, ExtensionOperation operation, DagContextImpl dag, DagConfig config) {
        var serDes = dagSerDes(config, context.getDurableConfig().getSerDes(), dag);
        return operation.runInChildContextAsync(
                DAG_SUBTYPE,
                TypeToken.get(DagResult.class),
                () -> executeDag(dag, config, serDes),
                ExtensionContextConfig.builder()
                        .serDes(serDes)
                        .suppressLateChildCheckpoints(true)
                        .build());
    }

    private static ExtensionContextResult<DagResult> executeDag(DagContextImpl dag, DagConfig config, SerDes serDes) {
        var result = DagResultImpl.from(DagExecutor.run(dag.tasks(), ExtensionContext.getCurrentContext(), config));
        var replayState = serDes instanceof DagResultSerDes dagSerDes ? dagSerDes.replayState(result) : null;
        return ExtensionContextResult.replayChildrenAboveSize(result, replayState, LARGE_RESULT_THRESHOLD);
    }

    // ── step ─────────────────────────────────────────────────────────────────
    @Override
    public <T> TaskHandle<T> step(String name, Class<T> type, DagStepFunction<T> fn) {
        return step(name, TypeToken.get(type), fn, StepConfig.builder().build());
    }

    @Override
    public <T> TaskHandle<T> step(String name, TypeToken<T> type, DagStepFunction<T> fn) {
        return step(name, type, fn, StepConfig.builder().build());
    }

    @Override
    public <T> TaskHandle<T> step(String name, Class<T> type, DagStepFunction<T> fn, StepConfig config) {
        return step(name, TypeToken.get(type), fn, config);
    }

    @Override
    public <T> TaskHandle<T> step(String name, TypeToken<T> type, DagStepFunction<T> fn, StepConfig config) {
        recordResultType(name, type);
        TaskExecutor<T> exec = (ctx, operation, deps) -> operation.stepAsync(
                STEP_SUBTYPE,
                type,
                ignored -> ExtensionStepResult.succeed(fn.apply(deps, StepContext.getCurrentContext())),
                ExtensionStepConfig.<T>builder()
                        .serDes(config.serDes())
                        .retryStrategy(adapt(config.retryStrategy()))
                        .semanticsPerRetry(adapt(config.semanticsPerRetry()))
                        .build());
        return register(new TaskHandleImpl<>(name, TaskKind.STEP, exec, config));
    }

    private static <T> ExtensionStepConfig.RetryStrategy<T> adapt(RetryStrategy retryStrategy) {
        return (error, state, attempt) -> {
            var decision = retryStrategy.makeRetryDecision(error, attempt);
            return decision.shouldRetry()
                    ? ExtensionStepResult.retry(state, decision.delay())
                    : ExtensionStepResult.doNotRetry();
        };
    }

    private static ExtensionStepConfig.StepSemantics adapt(StepSemantics semantics) {
        return switch (semantics) {
            case AT_LEAST_ONCE_PER_RETRY -> ExtensionStepConfig.StepSemantics.AT_LEAST_ONCE_PER_RETRY;
            case AT_MOST_ONCE_PER_RETRY -> ExtensionStepConfig.StepSemantics.AT_MOST_ONCE_PER_RETRY;
        };
    }

    // ── step: positional-arity typed-deps sugar (§2.7) ─────────────────────────
    @Override
    public <A, T> TaskHandle<T> step(String name, Class<T> type, TaskHandle<A> a, DagStep1Function<A, T> fn) {
        return step(name, type, (deps, sc) -> fn.apply(deps.get(a).orElse(null), sc))
                .reads(a);
    }

    @Override
    public <A, B, T> TaskHandle<T> step(
            String name, Class<T> type, TaskHandle<A> a, TaskHandle<B> b, DagStep2Function<A, B, T> fn) {
        return step(
                        name,
                        type,
                        (deps, sc) ->
                                fn.apply(deps.get(a).orElse(null), deps.get(b).orElse(null), sc))
                .reads(a, b);
    }

    @Override
    public <A, B, C, T> TaskHandle<T> step(
            String name,
            Class<T> type,
            TaskHandle<A> a,
            TaskHandle<B> b,
            TaskHandle<C> c,
            DagStep3Function<A, B, C, T> fn) {
        return step(
                        name,
                        type,
                        (deps, sc) -> fn.apply(
                                deps.get(a).orElse(null),
                                deps.get(b).orElse(null),
                                deps.get(c).orElse(null),
                                sc))
                .reads(a, b, c);
    }

    // ── invoke ───────────────────────────────────────────────────────────────
    @Override
    public <T> TaskHandle<T> invoke(String name, String functionName, Class<T> type, DagPayloadFunction payloadFn) {
        return invoke(
                name, functionName, type, payloadFn, InvokeConfig.builder().build());
    }

    @Override
    public <T> TaskHandle<T> invoke(
            String name, String functionName, Class<T> type, DagPayloadFunction payloadFn, InvokeConfig config) {
        var typeToken = TypeToken.get(type);
        recordResultType(name, typeToken);
        TaskExecutor<T> exec = (ctx, operation, deps) -> operation.invokeAsync(
                INVOKE_SUBTYPE,
                functionName,
                payloadFn.apply(deps),
                typeToken,
                ExtensionInvokeConfig.builder()
                        .payloadSerDes(config.payloadSerDes())
                        .serDes(config.serDes())
                        .tenantId(config.tenantId())
                        .build());
        return register(new TaskHandleImpl<>(name, TaskKind.INVOKE, exec, config));
    }

    // ── callback (submitter-based waitForCallback) ─────────────────────────────
    @Override
    public <T> TaskHandle<T> callback(String name, Class<T> type, DagCallbackSubmitter submitter) {
        return callback(name, type, submitter, WaitForCallbackConfig.builder().build());
    }

    @Override
    public <T> TaskHandle<T> callback(
            String name, Class<T> type, DagCallbackSubmitter submitter, WaitForCallbackConfig config) {
        var typeToken = TypeToken.get(type);
        recordResultType(name, typeToken);
        TaskExecutor<T> exec = (ctx, operation, deps) -> callback(operation, name, typeToken, submitter, deps, config);
        return register(new TaskHandleImpl<>(name, TaskKind.CALLBACK, exec, config));
    }

    private static <T> DurableFuture<T> callback(
            ExtensionOperation operation,
            String name,
            TypeToken<T> type,
            DagCallbackSubmitter submitter,
            Deps deps,
            WaitForCallbackConfig config) {
        return operation.runInChildContextAsync(
                CALLBACK_SUBTYPE,
                type,
                () -> ExtensionContextResult.replayChildrenAboveSize(
                        DurableWaitForCallbackOperation.waitForCallbackAsync(
                                        ExtensionContext.getCurrentContext(),
                                        name,
                                        type,
                                        (callbackId, step) -> submitter.apply(deps, callbackId, step),
                                        config.toOperationConfig())
                                .get(),
                        null,
                        LARGE_RESULT_THRESHOLD),
                ExtensionContextConfig.builder()
                        .serDes(config.stepConfig().serDes())
                        .build());
    }

    // ── wait ─────────────────────────────────────────────────────────────────
    @Override
    public TaskHandle<Void> wait(String name, Duration duration) {
        TaskExecutor<Void> exec = (ctx, operation, deps) -> operation.waitAsync(WAIT_SUBTYPE, duration);
        return register(new TaskHandleImpl<>(name, TaskKind.WAIT, exec, duration));
    }

    // ── waitForCondition ──────────────────────────────────────────────────────
    @Override
    public <S> TaskHandle<S> waitForCondition(
            String name, Class<S> type, DagConditionFunction<S> check, WaitForConditionConfig<S> config) {
        var typeToken = TypeToken.get(type);
        recordResultType(name, typeToken);
        TaskExecutor<S> exec = (ctx, operation, deps) -> DurableWaitForConditionOperation.waitForConditionAsync(
                new ReservedOperationContext(ctx, name, operation),
                name,
                typeToken,
                (state, step) -> {
                    var result = check.apply(deps, state, step);
                    return new DurableWaitForConditionOperation.WaitForConditionResult<>(
                            result.value(), result.isDone());
                },
                config.toOperationConfig());
        return register(new TaskHandleImpl<>(name, TaskKind.WAIT_FOR_CONDITION, exec, config));
    }

    // ── runInChildContext ──────────────────────────────────────────────────────
    @Override
    public <T> TaskHandle<T> runInChildContext(String name, Class<T> type, DagChildFunction<T> fn) {
        return runInChildContext(name, TypeToken.get(type), fn);
    }

    @Override
    public <T> TaskHandle<T> runInChildContext(String name, TypeToken<T> type, DagChildFunction<T> fn) {
        recordResultType(name, type);
        TaskExecutor<T> exec = (ctx, operation, deps) -> operation.runInChildContextAsync(
                RUN_IN_CHILD_CONTEXT_SUBTYPE,
                type,
                () -> ExtensionContextResult.replayChildrenAboveSize(
                        fn.apply(deps, DurableContext.getCurrentContext()), null, LARGE_RESULT_THRESHOLD),
                ExtensionContextConfig.builder().build());
        return register(new TaskHandleImpl<>(name, TaskKind.CHILD, exec, null));
    }

    // ── map ──────────────────────────────────────────────────────────────────
    @Override
    public <I, O> TaskHandle<MapResult<O>> map(String name, Collection<I> items, Class<O> type, MapFunction<I, O> fn) {
        return map(name, items, type, fn, MapConfig.builder().build());
    }

    @Override
    public <I, O> TaskHandle<MapResult<O>> map(
            String name, Collection<I> items, Class<O> type, MapFunction<I, O> fn, MapConfig config) {
        return map(name, deps -> items, type, fn, config);
    }

    @Override
    public <I, O> TaskHandle<MapResult<O>> map(
            String name, Function<Deps, Collection<I>> items, Class<O> type, MapFunction<I, O> fn) {
        return map(name, items, type, fn, MapConfig.builder().build());
    }

    @Override
    public <I, O> TaskHandle<MapResult<O>> map(
            String name, Function<Deps, Collection<I>> items, Class<O> type, MapFunction<I, O> fn, MapConfig config) {
        var typeToken = TypeToken.get(type);
        TaskExecutor<MapResult<O>> exec = (ctx, operation, deps) -> DurableMapOperation.mapAsync(
                new ReservedOperationContext(ctx, name, operation),
                name,
                items.apply(deps),
                typeToken,
                fn,
                config.toOperationConfig());
        return register(new TaskHandleImpl<>(name, TaskKind.MAP, exec, config));
    }

    // ── parallel ──────────────────────────────────────────────────────────────
    @Override
    public TaskHandle<ParallelResult> parallel(String name, Consumer<ParallelDurableFuture> branches) {
        return parallel(name, branches, ParallelConfig.builder().build());
    }

    @Override
    public TaskHandle<ParallelResult> parallel(
            String name, Consumer<ParallelDurableFuture> branches, ParallelConfig config) {
        recordResultType(name, TypeToken.get(ParallelResult.class));
        TaskExecutor<ParallelResult> exec = (ctx, operation, deps) -> {
            ParallelDurableFuture p = DurableParallelOperation.parallel(
                    new ReservedOperationContext(ctx, name, operation), name, config.toOperationConfig());
            branches.accept(p);
            return p;
        };
        return register(new TaskHandleImpl<>(name, TaskKind.PARALLEL, exec, config));
    }

    // ── nested dag ──────────────────────────────────────────────────────────────
    @Override
    public TaskHandle<DagResult> dag(String name, Consumer<DagContext> register) {
        return dag(name, register, DagConfig.builder().build());
    }

    @Override
    public TaskHandle<DagResult> dag(String name, Consumer<DagContext> register, DagConfig config) {
        // Register and validate the nested graph during the parent's registration phase so graph errors surface at
        // the top-level dag() call site before any extension operation is reserved.
        DagContextImpl nested = registerAndValidate(register);
        nestedContexts.put(name, nested);
        TaskExecutor<DagResult> exec = (ctx, operation, deps) -> start(ctx, operation, nested, config);
        return register(new TaskHandleImpl<>(name, TaskKind.DAG, exec, config));
    }
}
