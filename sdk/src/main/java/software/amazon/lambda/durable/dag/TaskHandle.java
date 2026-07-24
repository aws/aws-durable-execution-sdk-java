// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.function.Predicate;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * A registration-time reference to a DAG task, carrying the task's result type {@code T} via generics. Returned by each
 * {@link DagContext} registration method and used to declare dependencies and retrieve results via {@link Deps}.
 *
 * <p>Builder methods return {@code this} for fluent chaining. The handle's in-memory identity (not its name) is the key
 * used by the scheduler; it is never serialized.
 *
 * @param <T> the task's result type
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public interface TaskHandle<T> {

    /** The task name (runtime string; not a type-level literal). */
    String name();

    /**
     * Declares inline (typed) dependencies: the task waits for these AND can retrieve their results via
     * {@link Deps#get}. Only handles declared here are retrievable inside the task function.
     *
     * @param deps the upstream handles to read
     * @return this handle, for chaining
     */
    TaskHandle<T> reads(TaskHandle<?>... deps);

    /**
     * Declares ordering-only dependencies: the task waits for these but does NOT receive their results in {@link Deps}.
     *
     * @param deps the upstream handles to wait for
     * @return this handle, for chaining
     */
    TaskHandle<T> dependsOn(TaskHandle<?>... deps);

    /**
     * Sets the trigger rule (defaults to {@code DagConfig.defaultTriggerRule}, else {@link TriggerRule#ALL_SUCCESS}).
     *
     * @param rule the trigger rule
     * @return this handle, for chaining
     */
    TaskHandle<T> triggerRule(TriggerRule rule);

    /**
     * Sets a conditional-skip predicate evaluated (after the trigger rule passes) over resolved upstream results. When
     * it returns {@code false} the task is {@link TaskStatus#SKIPPED} with {@link SkipReason#RUN_IF_PREDICATE}.
     *
     * @param predicate the run-if predicate
     * @return this handle, for chaining
     */
    TaskHandle<T> runIf(Predicate<Deps> predicate);
}
