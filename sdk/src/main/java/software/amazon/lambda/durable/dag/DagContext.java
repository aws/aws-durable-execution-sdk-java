// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;
import software.amazon.lambda.durable.DurableContext.MapFunction;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.ParallelResult;

/**
 * Declarative task-registration surface passed to a {@code dag(...)} registration {@code Consumer}. Each method
 * registers one task and returns a {@link TaskHandle}; tasks are declared here but do not execute until registration
 * returns.
 *
 * <p>Does NOT extend {@code DurableContext}: only these declarative task methods are visible during registration.
 * Result typing uses the SDK's existing {@code Class<T>}/{@code TypeToken<T>} convention, and per-task config reuses
 * the SDK's existing config types verbatim.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public interface DagContext {

    // ── step ─────────────────────────────────────────────────────────────────
    <T> TaskHandle<T> step(String name, Class<T> type, DagStepFunction<T> fn);

    <T> TaskHandle<T> step(String name, TypeToken<T> type, DagStepFunction<T> fn);

    <T> TaskHandle<T> step(String name, Class<T> type, DagStepFunction<T> fn, StepConfig config);

    <T> TaskHandle<T> step(String name, TypeToken<T> type, DagStepFunction<T> fn, StepConfig config);

    // ── step: positional-arity typed-deps sugar (§2.7) ─────────────────────────
    // Compile-time-checked convenience overloads for the common 1..3 typed-dep case: each upstream result is passed to
    // the body directly (typed via the handle's generic), desugaring to step(...).reads(...) + Deps.get(...). For >3
    // deps or ordering-only edges, use the canonical step(...) + .reads(...)/.after(...) + Deps.get(...) form.
    <A, T> TaskHandle<T> step(String name, Class<T> type, TaskHandle<A> a, DagStep1Function<A, T> fn);

    <A, B, T> TaskHandle<T> step(
            String name, Class<T> type, TaskHandle<A> a, TaskHandle<B> b, DagStep2Function<A, B, T> fn);

    <A, B, C, T> TaskHandle<T> step(
            String name,
            Class<T> type,
            TaskHandle<A> a,
            TaskHandle<B> b,
            TaskHandle<C> c,
            DagStep3Function<A, B, C, T> fn);

    // ── invoke ───────────────────────────────────────────────────────────────
    <T> TaskHandle<T> invoke(String name, String functionName, Class<T> type, DagPayloadFunction payloadFn);

    <T> TaskHandle<T> invoke(
            String name, String functionName, Class<T> type, DagPayloadFunction payloadFn, InvokeConfig config);

    // ── callback ─────────────────────────────────────────────────────────────
    <T> TaskHandle<T> callback(String name, Class<T> type, DagCallbackSubmitter submitter);

    <T> TaskHandle<T> callback(
            String name, Class<T> type, DagCallbackSubmitter submitter, WaitForCallbackConfig config);

    // ── wait ─────────────────────────────────────────────────────────────────
    TaskHandle<Void> wait(String name, Duration duration);

    // ── waitForCondition ──────────────────────────────────────────────────────
    <S> TaskHandle<S> waitForCondition(
            String name, Class<S> type, DagConditionFunction<S> check, WaitForConditionConfig<S> config);

    // ── runInChildContext ─────────────────────────────────────────────────────
    <T> TaskHandle<T> runInChildContext(String name, Class<T> type, DagChildFunction<T> fn);

    <T> TaskHandle<T> runInChildContext(String name, TypeToken<T> type, DagChildFunction<T> fn);

    // ── map ──────────────────────────────────────────────────────────────────
    <I, O> TaskHandle<MapResult<O>> map(String name, Collection<I> items, Class<O> type, MapFunction<I, O> fn);

    <I, O> TaskHandle<MapResult<O>> map(
            String name, Collection<I> items, Class<O> type, MapFunction<I, O> fn, MapConfig config);

    <I, O> TaskHandle<MapResult<O>> map(
            String name, Function<Deps, Collection<I>> items, Class<O> type, MapFunction<I, O> fn);

    <I, O> TaskHandle<MapResult<O>> map(
            String name, Function<Deps, Collection<I>> items, Class<O> type, MapFunction<I, O> fn, MapConfig config);

    // ── parallel ──────────────────────────────────────────────────────────────
    // NOTE: branches are declared against the SDK's existing ParallelDurableFuture (reused verbatim, no new
    // ParallelBuilder type) — the scheduler applies the consumer to the launched parallel future.
    TaskHandle<ParallelResult> parallel(String name, Consumer<ParallelDurableFuture> branches);

    TaskHandle<ParallelResult> parallel(String name, Consumer<ParallelDurableFuture> branches, ParallelConfig config);

    // ── nested dag ────────────────────────────────────────────────────────────
    TaskHandle<DagResult> dag(String name, Consumer<DagContext> register);

    TaskHandle<DagResult> dag(String name, Consumer<DagContext> register, DagConfig config);
}
