// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableContext.MapFunction;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
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
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Concrete {@link DagContext}. Each registration method records a {@link TaskHandleImpl} whose {@link TaskExecutor}
 * closure launches the underlying operation through the matching {@code *AsyncWithId} entry point, prepending the
 * resolved {@link Deps} to the user function.
 */
public final class DagContextImpl implements DagContext {

    private final List<TaskHandleImpl<?>> tasks = new ArrayList<>();

    public List<TaskHandleImpl<?>> tasks() {
        return tasks;
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
     * This is deliberate: it lets a registration-time {@link software.amazon.lambda.durable.dag.DagException} propagate
     * <b>unwrapped</b> to the {@code dag()} caller (matching the spec's "throws at the {@code dag(...)} call site" and
     * the TS SDK's {@code errorMapper: (e) => e} pass-through), rather than being raised inside the
     * {@code runInChildContext} boundary where the typed exception would be erased into a generic
     * {@code ChildContextFailedException}.
     */
    public static DagContextImpl registerAndValidate(Consumer<DagContext> register) {
        var dctx = new DagContextImpl();
        register.accept(dctx);
        DagValidator.validate(dctx.tasks());
        return dctx;
    }

    /**
     * Builds the DAG child-context body: schedule → aggregate over an already-registered, already-validated context.
     * Shared by the top-level {@code dag()} entry point and nested DAG tasks. Registration and validation are performed
     * eagerly by {@link #registerAndValidate(Consumer)} at the call site (see that method for why).
     */
    public static Function<DurableContext, DagResult> body(DagContextImpl dctx, DagConfig config) {
        return childCtx -> {
            var outcome = DagExecutor.run(dctx.tasks(), (DurableContextImpl) childCtx, config);
            return DagResultImpl.from(outcome);
        };
    }

    /** Resolves the SerDes used to (de)serialize a DAG's aggregate result. */
    public static SerDes dagSerDes(DagConfig config, SerDes defaultSerDes) {
        return config.serDes().orElseGet(() -> new DagResultSerDes(defaultSerDes));
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
        TaskExecutor<T> exec = (ctx, deps, id) -> ctx.stepAsyncWithId(id, name, type, sc -> fn.apply(deps, sc), config);
        return register(new TaskHandleImpl<>(name, TaskKind.STEP, exec, config));
    }

    // ── step: positional-arity typed-deps sugar (§2.7) ─────────────────────────
    @Override
    public <A, T> TaskHandle<T> step(String name, Class<T> type, TaskHandle<A> a, DagStep1Function<A, T> fn) {
        return step(name, type, (deps, sc) -> fn.apply(deps.get(a), sc)).reads(a);
    }

    @Override
    public <A, B, T> TaskHandle<T> step(
            String name, Class<T> type, TaskHandle<A> a, TaskHandle<B> b, DagStep2Function<A, B, T> fn) {
        return step(name, type, (deps, sc) -> fn.apply(deps.get(a), deps.get(b), sc))
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
        return step(name, type, (deps, sc) -> fn.apply(deps.get(a), deps.get(b), deps.get(c), sc))
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
        TaskExecutor<T> exec = (ctx, deps, id) ->
                ctx.invokeAsyncWithId(id, name, functionName, payloadFn.apply(deps), typeToken, config);
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
        TaskExecutor<T> exec = (ctx, deps, id) -> {
            CallbackConfig callbackConfig = config.callbackConfig();
            StepConfig stepConfig = config.stepConfig();
            RunInChildContextConfig rc = RunInChildContextConfig.builder()
                    .serDes(stepConfig.serDes())
                    .build();
            return ctx.runInChildContextAsyncWithId(
                    id,
                    name,
                    typeToken,
                    childCtx -> {
                        var cb = childCtx.createCallback(name + "-callback", typeToken, callbackConfig);
                        childCtx.step(
                                name + "-submitter",
                                Void.class,
                                sc -> {
                                    submitter.apply(deps, cb.callbackId(), sc);
                                    return null;
                                },
                                stepConfig);
                        return cb.get();
                    },
                    rc);
        };
        return register(new TaskHandleImpl<>(name, TaskKind.CALLBACK, exec, config));
    }

    // ── wait ─────────────────────────────────────────────────────────────────
    @Override
    public TaskHandle<Void> wait(String name, Duration duration) {
        TaskExecutor<Void> exec = (ctx, deps, id) -> ctx.waitAsyncWithId(id, name, duration);
        return register(new TaskHandleImpl<>(name, TaskKind.WAIT, exec, duration));
    }

    // ── waitForCondition ──────────────────────────────────────────────────────
    @Override
    public <S> TaskHandle<S> waitForCondition(
            String name, Class<S> type, DagConditionFunction<S> check, WaitForConditionConfig<S> config) {
        var typeToken = TypeToken.get(type);
        TaskExecutor<S> exec = (ctx, deps, id) -> ctx.waitForConditionAsyncWithId(
                id, name, typeToken, (state, sc) -> check.apply(deps, state, sc), config);
        return register(new TaskHandleImpl<>(name, TaskKind.WAIT_FOR_CONDITION, exec, config));
    }

    // ── runInChildContext ──────────────────────────────────────────────────────
    @Override
    public <T> TaskHandle<T> runInChildContext(String name, Class<T> type, DagChildFunction<T> fn) {
        return runInChildContext(name, TypeToken.get(type), fn);
    }

    @Override
    public <T> TaskHandle<T> runInChildContext(String name, TypeToken<T> type, DagChildFunction<T> fn) {
        TaskExecutor<T> exec = (ctx, deps, id) -> ctx.runInChildContextAsyncWithId(
                id,
                name,
                type,
                childCtx -> fn.apply(deps, childCtx),
                RunInChildContextConfig.builder().build());
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
        TaskExecutor<MapResult<O>> exec =
                (ctx, deps, id) -> ctx.mapAsyncWithId(id, name, items.apply(deps), typeToken, fn, config);
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
        TaskExecutor<ParallelResult> exec = (ctx, deps, id) -> {
            ParallelDurableFuture p = ctx.parallelWithId(id, name, config);
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
        // Register + validate the nested graph eagerly (during the parent's registration phase, which itself runs at
        // the top-level dag() call site). A nested registration-time DagException therefore surfaces unwrapped at the
        // caller rather than being erased inside the child-context boundary.
        DagContextImpl nested = registerAndValidate(register);
        TaskExecutor<DagResult> exec = (ctx, deps, id) -> {
            RunInChildContextConfig rc = RunInChildContextConfig.builder()
                    .serDes(dagSerDes(config, ctx.getDurableConfig().getSerDes()))
                    .build();
            return ctx.runInChildContextAsyncWithId(id, name, TypeToken.get(DagResult.class), body(nested, config), rc);
        };
        return register(new TaskHandleImpl<>(name, TaskKind.DAG, exec, config));
    }
}
