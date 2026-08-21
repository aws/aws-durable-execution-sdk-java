// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.Map;
import java.util.Optional;
import software.amazon.lambda.durable.TypeToken;

/**
 * The declared result types of a DAG scope, keyed by task name, used to rehydrate {@code PLAIN} task results on replay
 * <em>without</em> persisting a class name in the checkpoint. Internal.
 *
 * <p>This is the replacement for the former checkpoint-stored {@code resultType} field. On replay we recover a
 * {@code PLAIN} result's type from the <em>registered graph</em> — the {@link TypeToken} the task was declared with —
 * looked up by the task's name, rather than from an untrusted class name read out of the checkpoint. Consequences:
 *
 * <ul>
 *   <li>The customer-facing envelope no longer carries a {@code resultType} field.
 *   <li>There is no {@code Class.forName} on any checkpoint-supplied string — only compile-time {@link TypeToken}s from
 *       the registered graph are ever used, so the arbitrary-class-load / static-initializer surface is removed
 *       entirely.
 *   <li>A task name absent from the map (unknown / tampered / never declared) simply falls back to a generic JSON tree.
 *   <li>Because we use the full declared {@link TypeToken}, generic element types (e.g. {@code List<Pojo>}) can now be
 *       rehydrated faithfully rather than erasing to a list of trees.
 * </ul>
 *
 * @param plain task name → declared result type for this scope's tasks (a map/parallel task's {@code MapResult} and a
 *     nested {@code dag}'s {@code DagResult} are rehydrated structurally and are not required here)
 * @param nested nested-{@code dag} task name → that nested scope's own {@link DagResultTypes}
 */
public record DagResultTypes(Map<String, TypeToken<?>> plain, Map<String, DagResultTypes> nested) {

    private static final DagResultTypes EMPTY = new DagResultTypes(Map.of(), Map.of());

    /** An empty type graph — nothing is reconstructable; every PLAIN result degrades to a generic JSON tree. */
    public static DagResultTypes empty() {
        return EMPTY;
    }

    public DagResultTypes {
        plain = Map.copyOf(plain);
        nested = Map.copyOf(nested);
    }

    /** The declared type for a PLAIN task result by name, if the task was registered in this scope. */
    public Optional<TypeToken<?>> plainType(String taskName) {
        return Optional.ofNullable(plain.get(taskName));
    }

    /** The nested scope for a nested-{@code dag} task by name, or an empty graph if unknown. */
    public DagResultTypes nestedScope(String taskName) {
        return nested.getOrDefault(taskName, EMPTY);
    }
}
