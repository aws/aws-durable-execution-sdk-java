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
    private final List<String> startedTaskNames;

    /**
     * Explicit per-status counts, used when this result was restored from an offloaded (tasks-less) envelope where the
     * per-task {@link #results} map is legitimately empty but the aggregate counts are carried by the envelope and MUST
     * be preserved (see the nested-offload contract, rule 1). {@code null} means "derive from the {@link #results}
     * map", which is the correct behaviour for a live scheduler outcome and for an inline (tasks-carrying) round-trip
     * where the map is fully populated.
     */
    private final Integer explicitSuccessCount;

    private final Integer explicitFailureCount;
    private final Integer explicitSkippedCount;

    /**
     * Backward-compatible constructor for callers where every registered task settled (total == settled map size), e.g.
     * unit tests and small-DAG serde round-trips that don't model early completion.
     */
    public DagResultImpl(Map<String, TaskExecution<?>> results, DagCompletionReason completionReason) {
        this(results, completionReason, results.size());
    }

    public DagResultImpl(Map<String, TaskExecution<?>> results, DagCompletionReason completionReason, int totalCount) {
        this(results, completionReason, totalCount, List.of());
    }

    public DagResultImpl(
            Map<String, TaskExecution<?>> results,
            DagCompletionReason completionReason,
            int totalCount,
            List<String> startedTaskNames) {
        this(results, completionReason, totalCount, startedTaskNames, null, null, null);
    }

    /**
     * Full constructor that allows the three per-status counts to be supplied explicitly rather than derived from the
     * {@code results} map. This is what preserves the aggregate when restoring from an offloaded envelope whose
     * {@code tasks} list was dropped: the map is empty, but {@code successCount}/{@code failureCount}/
     * {@code skippedCount} still report the values the envelope carried (contract rule 1). Pass {@code null} for a
     * count to derive it from the map (the inline / live-outcome case).
     */
    public DagResultImpl(
            Map<String, TaskExecution<?>> results,
            DagCompletionReason completionReason,
            int totalCount,
            List<String> startedTaskNames,
            Integer successCount,
            Integer failureCount,
            Integer skippedCount) {
        this.results = new LinkedHashMap<>(results);
        this.completionReason = completionReason;
        this.totalCount = totalCount;
        this.startedTaskNames = List.copyOf(startedTaskNames);
        this.explicitSuccessCount = successCount;
        this.explicitFailureCount = failureCount;
        this.explicitSkippedCount = skippedCount;
    }

    public static DagResultImpl from(DagExecutionOutcome outcome) {
        return new DagResultImpl(
                outcome.results(), outcome.completionReason(), outcome.totalCount(), outcome.startedTaskNames());
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
        return explicitSuccessCount != null
                ? explicitSuccessCount
                : byStatus(TaskStatus.SUCCEEDED).size();
    }

    @Override
    public int failureCount() {
        return explicitFailureCount != null
                ? explicitFailureCount
                : byStatus(TaskStatus.FAILED).size();
    }

    @Override
    public int skippedCount() {
        return explicitSkippedCount != null
                ? explicitSkippedCount
                : byStatus(TaskStatus.SKIPPED).size();
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
    public List<String> startedTaskNames() {
        return startedTaskNames;
    }

    @Override
    public void throwIfError() {
        if (failureCount() > 0) {
            var failedList = failed();
            if (failedList.isEmpty()) {
                // Restored from an offloaded (tasks-less) envelope: the aggregate states the DAG had failures, but the
                // per-task detail is not present in this result (it lives in the retained child operations). Still
                // honour the contract — never report success when the checkpoint says otherwise — by throwing with the
                // aggregate failure count rather than a specific task.
                throw new DagExecutionException(
                        "DAG completed with " + failureCount()
                                + " failed task(s); per-task detail unavailable (result restored from an offloaded checkpoint)");
            }
            var first = failedList.get(0);
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
