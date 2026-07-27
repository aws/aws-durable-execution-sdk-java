// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import software.amazon.lambda.durable.dag.Deps;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskHandle;
import software.amazon.lambda.durable.dag.TaskStatus;

/**
 * {@link Deps} implementation backing a single task. Only the task's inline dependencies (declared via
 * {@code reads(...)}) are retrievable; results are resolved by task name from an <b>immutable per-task snapshot</b> of
 * those dependencies' terminal executions, taken by the scheduler at launch time (all inline deps are terminal before
 * the task is launched). The snapshot is private to this task, so reads here never race the scheduler's writes to its
 * live results map.
 */
final class DepsImpl implements Deps {

    private final Set<TaskHandle<?>> inlineDeps;
    private final Map<String, TaskExecution<?>> results;

    DepsImpl(java.util.List<TaskHandle<?>> inlineDeps, Map<String, TaskExecution<?>> results) {
        Set<TaskHandle<?>> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(inlineDeps);
        this.inlineDeps = set;
        this.results = results;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> get(TaskHandle<T> handle) {
        requireDeclared(handle);
        var exec = results.get(handle.name());
        if (exec == null || exec.status() != TaskStatus.SUCCEEDED) {
            return Optional.empty();
        }
        return (Optional<T>) exec.result();
    }

    private void requireDeclared(TaskHandle<?> handle) {
        if (!inlineDeps.contains(handle)) {
            throw new IllegalStateException("Task result for '" + handle.name()
                    + "' is not retrievable: it was not declared as an inline dependency via reads(...)");
        }
    }
}
