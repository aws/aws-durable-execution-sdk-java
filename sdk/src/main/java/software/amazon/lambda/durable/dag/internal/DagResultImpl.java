// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.DagExecutionException;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskHandle;
import software.amazon.lambda.durable.dag.TaskStatus;

/** Concrete {@link DagResult} backed by the scheduler's terminal task-state map. */
public final class DagResultImpl implements DagResult {

    private final Map<String, TaskExecution<?>> results;
    private final DagCompletionReason completionReason;
    private final int totalCount;

    /**
     * Backward-compatible constructor for callers where every registered task settled (total == settled map size), e.g.
     * unit tests and small-DAG serde round-trips that don't model early completion.
     */
    public DagResultImpl(Map<String, TaskExecution<?>> results, DagCompletionReason completionReason) {
        this(results, completionReason, results.size());
    }

    public DagResultImpl(Map<String, TaskExecution<?>> results, DagCompletionReason completionReason, int totalCount) {
        this.results = new LinkedHashMap<>(results);
        this.completionReason = completionReason;
        this.totalCount = totalCount;
    }

    public static DagResultImpl from(DagExecutionOutcome outcome) {
        return new DagResultImpl(outcome.results(), outcome.completionReason(), outcome.totalCount());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> getResult(TaskHandle<T> handle) {
        return (Optional<T>) getResult(handle.name());
    }

    @Override
    public Optional<Object> getResult(String name) {
        var exec = results.get(name);
        if (exec == null || exec.status() != TaskStatus.SUCCEEDED) {
            return Optional.empty();
        }
        return Optional.ofNullable(exec.result().orElse(null));
    }

    @Override
    public Optional<TaskStatus> getStatus(TaskHandle<?> handle) {
        return getStatus(handle.name());
    }

    @Override
    public Optional<TaskStatus> getStatus(String name) {
        var exec = results.get(name);
        return exec == null ? Optional.empty() : Optional.of(exec.status());
    }

    @Override
    public List<TaskExecution<?>> succeeded() {
        return byStatus(TaskStatus.SUCCEEDED);
    }

    @Override
    public List<TaskExecution<?>> failed() {
        return byStatus(TaskStatus.FAILED);
    }

    @Override
    public List<TaskExecution<?>> skipped() {
        return byStatus(TaskStatus.SKIPPED);
    }

    private List<TaskExecution<?>> byStatus(TaskStatus status) {
        List<TaskExecution<?>> list = new ArrayList<>();
        for (var e : results.values()) {
            if (e.status() == status) {
                list.add(e);
            }
        }
        return list;
    }

    @Override
    public Map<String, TaskExecution<?>> results() {
        return Collections.unmodifiableMap(results);
    }

    @Override
    public int successCount() {
        return byStatus(TaskStatus.SUCCEEDED).size();
    }

    @Override
    public int failureCount() {
        return byStatus(TaskStatus.FAILED).size();
    }

    @Override
    public int skippedCount() {
        return byStatus(TaskStatus.SKIPPED).size();
    }

    @Override
    public int totalCount() {
        return totalCount;
    }

    @Override
    public DagCompletionReason completionReason() {
        return completionReason;
    }

    @Override
    public void throwIfError() {
        if (failureCount() > 0) {
            var first = failed().get(0);
            var cause = first.error().flatMap(e -> e.cause()).orElse(null);
            var message = "DAG completed with " + failureCount() + " failed task(s); first failure: '"
                    + first.name() + "'"
                    + first.error()
                            .map(e -> " (" + e.errorType() + ": " + e.errorMessage() + ")")
                            .orElse("");
            if (cause != null) {
                throw new DagExecutionException(message, cause);
            }
            throw new DagExecutionException(message);
        }
    }
}
