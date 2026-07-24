// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import software.amazon.lambda.durable.dag.Deps;
import software.amazon.lambda.durable.dag.TaskHandle;
import software.amazon.lambda.durable.dag.TriggerRule;

/**
 * Immutable snapshot of a registered task, consumed by the scheduler.
 *
 * @param <T> the task result type
 * @param name the task name
 * @param kind the underlying operation kind
 * @param inlineDeps handles declared via {@code reads(...)} (drive {@link Deps})
 * @param allDeps inline ∪ ordering-only deps (drive readiness, trigger rules, cycle detection)
 * @param triggerRule optional per-task trigger rule
 * @param runIf optional conditional-skip predicate
 * @param options the per-task config object (may be {@code null})
 * @param executor the closure that launches the underlying operation under an explicit ID
 */
public record TaskDef<T>(
        String name,
        TaskKind kind,
        List<TaskHandle<?>> inlineDeps,
        List<TaskHandle<?>> allDeps,
        Optional<TriggerRule> triggerRule,
        Optional<Predicate<Deps>> runIf,
        Object options,
        TaskExecutor<T> executor) {}
