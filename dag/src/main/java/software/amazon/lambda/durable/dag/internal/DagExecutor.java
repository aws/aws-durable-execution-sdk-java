// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.dag.CustomDagCompletion;
import software.amazon.lambda.durable.dag.DagCompletionConfig;
import software.amazon.lambda.durable.dag.DagCompletionDecision;
import software.amazon.lambda.durable.dag.DagCompletionItemStatus;
import software.amazon.lambda.durable.dag.DagCompletionOutcome;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.DagCompletionStatus;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagPredicateException;
import software.amazon.lambda.durable.dag.DagTaskError;
import software.amazon.lambda.durable.dag.Deps;
import software.amazon.lambda.durable.dag.SkipReason;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskHandle;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.dag.ThresholdDagCompletion;
import software.amazon.lambda.durable.dag.TriggerRule;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;

/**
 * Topological DAG scheduler. Runs on the DAG child-context thread, reserves each task under the stable local ID
 * {@code "DAG_NODE_T_" + name}, enforces {@code maxConcurrency}, and awaits results via {@link DurableFuture#get()}.
 *
 * <p>Failures are terminal task states, not aborts: by default the scheduler drains the reachable graph so compensation
 * tasks (ALL_FAILED / ALL_DONE) run. Skips (trigger-rule / runIf) are terminal, cascade downstream, and checkpoint
 * nothing.
 */
public final class DagExecutor {

    /** Prefix applied to task names before minting name-based operation IDs. */
    public static final String NODE_PREFIX = "DAG_NODE_T_";

    /**
     * Default cap on the number of top-level tasks a DAG runs concurrently when {@link DagConfig#maxConcurrency()} is
     * unset. Bounds the DAG scheduler only (one level, top-level tasks); it is not inherited by a task's own internal
     * fan-out (a {@code map}/{@code parallel} task keeps its unlimited default, and a nested {@code dag} gets its own
     * independent default of 40). An explicit {@code maxConcurrency} always wins, including values above 40. See the
     * cross-SDK default-concurrency contract (review finding H2).
     */
    public static final int DEFAULT_MAX_CONCURRENCY = 40;

    private DagExecutor() {}

    /**
     * Runs the scheduler to completion (may suspend/replay).
     *
     * @param tasks the registered tasks, in registration order (already validated)
     * @param childCtx the DAG child context to launch operations in
     * @param config the DAG configuration
     * @return the terminal outcome
     */
    public static DagExecutionOutcome run(List<TaskHandleImpl<?>> tasks, ExtensionContext childCtx, DagConfig config) {

        int maxConcurrency = config.maxConcurrency().orElse(DEFAULT_MAX_CONCURRENCY);
        TriggerRule defaultRule = config.defaultTriggerRule().orElse(TriggerRule.ALL_SUCCESS);
        Optional<DagCompletionConfig> completion = config.completionConfig();
        int totalTaskCount = tasks.size();

        Map<String, TaskExecution<?>> results = new LinkedHashMap<>();
        Map<String, ExtensionOperation> operations = reserveOperations(tasks, childCtx);
        // name -> (task, future); insertion order = launch order
        LinkedHashMap<String, InFlight> inFlight = new LinkedHashMap<>();

        DagCompletionReason earlyReason = null;
        List<String> startedTaskNames = new ArrayList<>();

        fillReady(tasks, childCtx, operations, defaultRule, maxConcurrency, results, inFlight);

        while (!inFlight.isEmpty()) {
            var it = inFlight.entrySet().iterator();
            var entry = it.next();
            it.remove();
            String name = entry.getKey();
            try {
                Object result = entry.getValue().future.get();
                results.put(name, succeeded(name, result));
            } catch (UnrecoverableDurableExecutionException e) {
                throw e;
            } catch (RuntimeException e) {
                results.put(name, failed(name, DagTaskError.of(e)));
            }

            var reason = evaluateEarlyCompletion(completion, tasks, results, totalTaskCount);
            if (reason != null) {
                earlyReason = reason;
                // Early completion: capture the tasks that were launched but had not reached a terminal state at the
                // deterministic stop point (in launch order). These are excluded from `results`, but the envelope
                // records them as startedTaskNames — the started set that no child operation records, and which a
                // large-payload reconstruct must preserve. On replay the scheduler re-evaluates completion
                // deterministically, reaches the identical stop point, and reproduces this exact set.
                startedTaskNames = new ArrayList<>(inFlight.keySet());
                // Stop launching/awaiting and abandon any still-in-flight tasks. This is deliberate and replay-safe:
                // each in-flight op was launched under its name-based ID
                // (idOf(name)), so any late checkpoint it writes is inert on replay — the scheduler
                // re-evaluates completion deterministically, reaches the identical stop point, and never
                // reads a checkpoint for a task past that point (spec §8.1(3)). Abandoned tasks are therefore
                // excluded from `results`, preserving the minimal-set early-completion semantics. We drop the
                // in-flight references so no further waves are launched.
                inFlight.clear();
                break;
            }
            fillReady(tasks, childCtx, operations, defaultRule, maxConcurrency, results, inFlight);
        }

        DagCompletionReason completionReason;
        if (earlyReason != null) {
            completionReason = earlyReason;
        } else {
            completionReason = countByStatus(results, TaskStatus.FAILED) > 0
                    ? DagCompletionReason.COMPLETED_WITH_FAILURES
                    : DagCompletionReason.ALL_COMPLETED;
        }

        // Rebuild in registration order for deterministic output.
        Map<String, TaskExecution<?>> ordered = new LinkedHashMap<>();
        for (var task : tasks) {
            var exec = results.get(task.name());
            if (exec != null) {
                ordered.put(task.name(), exec);
            }
        }
        return new DagExecutionOutcome(ordered, startedTaskNames, completionReason, totalTaskCount);
    }

    private static Map<String, ExtensionOperation> reserveOperations(
            List<TaskHandleImpl<?>> tasks, ExtensionContext context) {
        Map<String, ExtensionOperation> operations = new HashMap<>();
        for (var task : tasks) {
            operations.put(task.name(), context.reserve(task.name(), NODE_PREFIX + task.name()));
        }
        return operations;
    }

    /** Launches/skips every currently-ready task, up to the concurrency cap. Idempotent within a wave. */
    private static void fillReady(
            List<TaskHandleImpl<?>> tasks,
            ExtensionContext childCtx,
            Map<String, ExtensionOperation> operations,
            TriggerRule defaultRule,
            int maxConcurrency,
            Map<String, TaskExecution<?>> results,
            LinkedHashMap<String, InFlight> inFlight) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var task : tasks) {
                String name = task.name();
                if (results.containsKey(name) || inFlight.containsKey(name)) {
                    continue;
                }
                if (!depsTerminal(task, results)) {
                    continue;
                }
                var statuses = depStatuses(task, results);
                var rule = task.triggerRuleOpt().orElse(defaultRule);
                if (!TriggerRuleEvaluator.eval(rule, statuses)) {
                    results.put(name, skipped(name, SkipReason.TRIGGER_RULE));
                    changed = true;
                    continue;
                }
                // B1 fix: snapshot THIS task's inline deps into an immutable map and hand that to DepsImpl instead of
                // the scheduler's live `results` map. Every inline dep is already terminal here (depsTerminal(task,
                // results) passed above), so the snapshot is complete and its values never change. This removes the
                // shared mutable state entirely: task bodies (which call deps.get(...) on user-executor threads) read
                // their own private, immutable view rather than racing the scheduler thread's results.put(...) writes
                // on a non-thread-safe LinkedHashMap. Deps semantics are unchanged (requireDeclared + SUCCEEDED-only).
                Map<String, TaskExecution<?>> depsSnapshot = new HashMap<>();
                for (TaskHandle<?> dep : task.inlineDeps()) {
                    depsSnapshot.put(dep.name(), results.get(dep.name()));
                }
                Deps deps = new DepsImpl(task.name(), task.inlineDeps(), Collections.unmodifiableMap(depsSnapshot));
                if (task.runIfOpt().isPresent()) {
                    boolean run;
                    try {
                        run = task.runIfOpt().get().test(deps);
                    } catch (RuntimeException t) {
                        // A runIf predicate is specified as synchronous/deterministic/pure; a throw is a defect in
                        // deterministic code, NOT a business outcome. Abort the DAG with a typed error instead of
                        // recording the task FAILED (which would fire ALL_FAILED/ANY_FAILED/ALL_DONE compensation) or
                        // SKIPPED. The offending task gets no terminal state and no further tasks are launched; the
                        // throw escapes the scheduler and fails the DAG child-context body. See DagPredicateException.
                        throw new DagPredicateException(name, t);
                    }
                    if (!run) {
                        results.put(name, skipped(name, SkipReason.RUN_IF_PREDICATE));
                        changed = true;
                        continue;
                    }
                }
                if (inFlight.size() < maxConcurrency) {
                    DurableFuture<?> future = launch(task, childCtx, operations.get(name), deps);
                    inFlight.put(name, new InFlight(future));
                    changed = true;
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DurableFuture<?> launch(
            TaskHandleImpl<?> task, ExtensionContext ctx, ExtensionOperation operation, Deps deps) {
        TaskExecutor executor = task.executor();
        return executor.launch(ctx, operation, deps);
    }

    private static boolean depsTerminal(TaskHandleImpl<?> task, Map<String, TaskExecution<?>> results) {
        for (TaskHandle<?> dep : task.allDeps()) {
            if (!results.containsKey(dep.name())) {
                return false;
            }
        }
        return true;
    }

    private static List<TaskStatus> depStatuses(TaskHandleImpl<?> task, Map<String, TaskExecution<?>> results) {
        List<TaskStatus> statuses = new ArrayList<>();
        for (TaskHandle<?> dep : task.allDeps()) {
            statuses.add(results.get(dep.name()).status());
        }
        return statuses;
    }

    private static DagCompletionReason evaluateEarlyCompletion(
            Optional<DagCompletionConfig> completion,
            List<TaskHandleImpl<?>> tasks,
            Map<String, TaskExecution<?>> results,
            int totalTaskCount) {
        if (completion.isEmpty()) {
            return null; // default: drain the whole reachable graph
        }
        var dcc = completion.get();
        if (dcc instanceof CustomDagCompletion custom) {
            DagCompletionStatus status = buildCompletionStatus(tasks, results, totalTaskCount);
            DagCompletionDecision decision = custom.shouldComplete().apply(status);
            if (decision.complete()) {
                return decision.outcome() == DagCompletionOutcome.FAILED
                        ? DagCompletionReason.CUSTOM_COMPLETION_FAILED
                        : DagCompletionReason.CUSTOM_COMPLETION_SUCCEEDED;
            }
            return null;
        }
        var cc = unwrap(dcc);
        int succeeded = countByStatus(results, TaskStatus.SUCCEEDED);
        int failed = countByStatus(results, TaskStatus.FAILED);
        if (cc.minSuccessful() != null && succeeded >= cc.minSuccessful()) {
            return DagCompletionReason.MIN_SUCCESSFUL_REACHED;
        }
        if (cc.toleratedFailureCount() != null && failed > cc.toleratedFailureCount()) {
            return DagCompletionReason.FAILURE_TOLERANCE_EXCEEDED;
        }
        if (cc.toleratedFailurePercentage() != null
                && totalTaskCount > 0
                && (double) failed / totalTaskCount > cc.toleratedFailurePercentage()) {
            return DagCompletionReason.FAILURE_TOLERANCE_EXCEEDED;
        }
        return null;
    }

    /**
     * Builds the live progress snapshot passed to a custom {@code shouldComplete} predicate: every task in registration
     * order, keyed by name, reflecting exactly what has settled so far (tasks with no entry in {@code results} yet are
     * reported with an empty status, i.e. not yet started).
     */
    private static DagCompletionStatus buildCompletionStatus(
            List<TaskHandleImpl<?>> tasks, Map<String, TaskExecution<?>> results, int totalTaskCount) {
        List<DagCompletionItemStatus> items = new ArrayList<>(tasks.size());
        Map<String, DagCompletionItemStatus> byName = new LinkedHashMap<>();
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        for (TaskHandleImpl<?> task : tasks) {
            TaskExecution<?> exec = results.get(task.name());
            DagCompletionItemStatus item;
            if (exec == null) {
                item = new DagCompletionItemStatus(task.name(), Optional.empty(), Optional.empty(), Optional.empty());
            } else {
                item = new DagCompletionItemStatus(
                        task.name(),
                        Optional.of(exec.status()),
                        Optional.ofNullable(exec.result().orElse(null)),
                        exec.skipReason());
                switch (exec.status()) {
                    case SUCCEEDED -> succeeded++;
                    case FAILED -> failed++;
                    case SKIPPED -> skipped++;
                    default -> {
                        // STARTED tasks are never present in `results` (only terminal states are recorded there),
                        // so this branch is unreachable; kept for exhaustiveness.
                    }
                }
            }
            items.add(item);
            byName.put(task.name(), item);
        }
        int completedCount = succeeded + failed + skipped;
        return new DagCompletionStatus(succeeded, failed, skipped, completedCount, totalTaskCount, items, byName);
    }

    private static CompletionConfig unwrap(DagCompletionConfig dcc) {
        if (dcc instanceof ThresholdDagCompletion threshold) {
            return threshold.completionConfig();
        }
        throw new IllegalStateException("Unsupported DagCompletionConfig: " + dcc);
    }

    private static int countByStatus(Map<String, TaskExecution<?>> results, TaskStatus status) {
        int n = 0;
        for (var e : results.values()) {
            if (e.status() == status) {
                n++;
            }
        }
        return n;
    }

    private static TaskExecution<Object> succeeded(String name, Object result) {
        return new TaskExecution<>(
                name,
                TaskStatus.SUCCEEDED,
                Optional.empty(),
                Optional.ofNullable(result),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static TaskExecution<Object> failed(String name, DagTaskError error) {
        return new TaskExecution<>(
                name,
                TaskStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(error),
                Optional.empty(),
                Optional.empty());
    }

    private static TaskExecution<Object> skipped(String name, SkipReason reason) {
        return new TaskExecution<>(
                name,
                TaskStatus.SKIPPED,
                Optional.of(reason),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** In-flight task launch. */
    private record InFlight(DurableFuture<?> future) {}
}
