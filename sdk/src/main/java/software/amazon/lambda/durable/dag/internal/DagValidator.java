// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;
import software.amazon.lambda.durable.dag.DagCyclicDependencyException;
import software.amazon.lambda.durable.dag.DagDuplicateTaskException;
import software.amazon.lambda.durable.dag.DagInvalidDependencyException;
import software.amazon.lambda.durable.dag.DagInvalidTaskNameException;
import software.amazon.lambda.durable.dag.TaskHandle;

/**
 * Registration-time DAG graph validation. Runs once, after registration returns, before any task launches. Enforces the
 * DAG-layer name charset (stricter than the base SDK), rejects duplicates and foreign dependencies, and detects cycles
 * via Kahn's algorithm ({@code O(V+E)}). A diamond is not a cycle.
 */
public final class DagValidator {

    /** DAG-layer task-name pattern: alphanumeric + underscore only, no dash. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    private static final int MAX_NAME_LENGTH = 100;

    /** Reserved structural delimiter that names must not contain (defense-in-depth for name-based IDs). */
    private static final String RESERVED_DELIMITER = "DAG_NODE_T_";

    private DagValidator() {}

    /**
     * Validates the registered tasks (in registration order).
     *
     * @param tasks the registered task handles
     * @throws DagInvalidTaskNameException on a bad task name
     * @throws DagDuplicateTaskException on a duplicate name
     * @throws DagInvalidDependencyException on a dependency not registered in this scope
     * @throws DagCyclicDependencyException if the dependency graph contains a cycle
     */
    public static void validate(List<TaskHandleImpl<?>> tasks) {
        Set<TaskHandle<?>> registered =
                java.util.Collections.newSetFromMap(new IdentityHashMap<TaskHandle<?>, Boolean>());
        var names = new HashSet<String>();

        for (var task : tasks) {
            validateName(task.name());
            if (!names.add(task.name())) {
                throw new DagDuplicateTaskException("Duplicate DAG task name: '" + task.name() + "'");
            }
            registered.add(task);
        }

        // Foreign / unregistered dependency check.
        for (var task : tasks) {
            for (var dep : task.allDeps()) {
                if (!registered.contains(dep)) {
                    throw new DagInvalidDependencyException("Task '" + task.name() + "' depends on task '" + dep.name()
                            + "' which is not registered in this DAG scope");
                }
            }
        }

        detectCycles(tasks);
    }

    private static void validateName(String name) {
        if (name == null || name.isEmpty()) {
            throw new DagInvalidTaskNameException("DAG task name must be non-empty");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new DagInvalidTaskNameException(
                    "DAG task name exceeds " + MAX_NAME_LENGTH + " chars: '" + name + "'");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new DagInvalidTaskNameException("DAG task name must match ^[a-zA-Z0-9_]+$ (no dash): '" + name + "'");
        }
        if (name.contains(RESERVED_DELIMITER)) {
            throw new DagInvalidTaskNameException("DAG task name must not contain the reserved sequence '"
                    + RESERVED_DELIMITER + "': '" + name + "'");
        }
    }

    /** Kahn's algorithm over {@code allDeps}. */
    private static void detectCycles(List<TaskHandleImpl<?>> tasks) {
        // Map each handle to its outstanding in-degree (number of unresolved dependencies).
        Map<TaskHandle<?>, Integer> inDegree = new IdentityHashMap<>();
        // dependents: dep -> list of tasks that depend on it.
        Map<TaskHandle<?>, java.util.List<TaskHandle<?>>> dependents = new IdentityHashMap<>();

        for (var task : tasks) {
            inDegree.putIfAbsent(task, 0);
            dependents.putIfAbsent(task, new java.util.ArrayList<>());
        }
        for (var task : tasks) {
            for (var dep : task.allDeps()) {
                inDegree.merge(task, 1, Integer::sum);
                dependents.get(dep).add(task);
            }
        }

        Queue<TaskHandle<?>> ready = new ArrayDeque<>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }

        int resolved = 0;
        while (!ready.isEmpty()) {
            var t = ready.poll();
            resolved++;
            for (var dependent : dependents.get(t)) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (resolved != tasks.size()) {
            var cyclic = new HashMap<String, Boolean>();
            for (var e : inDegree.entrySet()) {
                if (e.getValue() > 0) {
                    cyclic.put(((TaskHandle<?>) e.getKey()).name(), true);
                }
            }
            throw new DagCyclicDependencyException(
                    "DAG contains a cyclic dependency involving tasks: " + cyclic.keySet());
        }
    }
}
