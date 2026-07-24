// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import software.amazon.lambda.durable.dag.Deps;
import software.amazon.lambda.durable.dag.TaskHandle;
import software.amazon.lambda.durable.dag.TriggerRule;

/**
 * Concrete, mutable {@link TaskHandle} produced during DAG registration. Carries the task's kind, executor closure,
 * declared dependencies, trigger rule, and {@code runIf} predicate. The scheduler snapshots it to an immutable
 * {@link TaskDef} via {@link #toTaskDef()}.
 *
 * @param <T> the task result type
 */
public final class TaskHandleImpl<T> implements TaskHandle<T> {

    private final String name;
    private final TaskKind kind;
    private final TaskExecutor<T> executor;
    private final Object options;
    private final List<TaskHandle<?>> inlineDeps = new ArrayList<>();
    private final List<TaskHandle<?>> extraDeps = new ArrayList<>();
    private TriggerRule triggerRule;
    private Predicate<Deps> runIf;

    public TaskHandleImpl(String name, TaskKind kind, TaskExecutor<T> executor, Object options) {
        this.name = name;
        this.kind = kind;
        this.executor = executor;
        this.options = options;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public TaskHandle<T> reads(TaskHandle<?>... deps) {
        for (var d : deps) {
            if (!inlineDeps.contains(d)) {
                inlineDeps.add(d);
            }
        }
        return this;
    }

    @Override
    public TaskHandle<T> dependsOn(TaskHandle<?>... deps) {
        for (var d : deps) {
            if (!extraDeps.contains(d)) {
                extraDeps.add(d);
            }
        }
        return this;
    }

    @Override
    public TaskHandle<T> triggerRule(TriggerRule rule) {
        this.triggerRule = rule;
        return this;
    }

    @Override
    public TaskHandle<T> runIf(Predicate<Deps> predicate) {
        this.runIf = predicate;
        return this;
    }

    public TaskKind kind() {
        return kind;
    }

    public TaskExecutor<T> executor() {
        return executor;
    }

    public List<TaskHandle<?>> inlineDeps() {
        return inlineDeps;
    }

    /** Union of inline (reads) and ordering-only (dependsOn) dependencies, de-duplicated, inline first. */
    public List<TaskHandle<?>> allDeps() {
        var all = new ArrayList<TaskHandle<?>>(inlineDeps);
        for (var d : extraDeps) {
            if (!all.contains(d)) {
                all.add(d);
            }
        }
        return all;
    }

    public Optional<TriggerRule> triggerRuleOpt() {
        return Optional.ofNullable(triggerRule);
    }

    public Optional<Predicate<Deps>> runIfOpt() {
        return Optional.ofNullable(runIf);
    }

    /** Immutable snapshot for the scheduler. */
    public TaskDef<T> toTaskDef() {
        return new TaskDef<>(
                name,
                kind,
                List.copyOf(inlineDeps),
                List.copyOf(allDeps()),
                triggerRuleOpt(),
                runIfOpt(),
                options,
                executor);
    }
}
