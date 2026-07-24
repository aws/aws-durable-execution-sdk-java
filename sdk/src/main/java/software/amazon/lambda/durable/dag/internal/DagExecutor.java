// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.dag.DagCompletionConfig;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagTaskError;
import software.amazon.lambda.durable.dag.Deps;
import software.amazon.lambda.durable.dag.SkipReason;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskHandle;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.dag.ThresholdDagCompletion;
import software.amazon.lambda.durable.dag.TriggerRule;
import software.amazon.lambda.durable.execution.SuspendExecutionException;

/**
 * Topological DAG scheduler. Runs on the DAG child-context thread: launches each ready task through its explicit-ID
 * {@code *AsyncWithId} executor closure under {@code idOf(name) = operationIdForName("DAG_NODE_T_" + name)}, enforces
 * {@code maxConcurrency} by deferring the launch, and awaits results via {@link DurableFuture#get()} (which handles
 * suspend/replay). The scheduler owns no threads or executor.
 *
 * <p>Failures are terminal task states, not aborts: by default the scheduler drains the reachable graph so compensation
 * tasks (ALL_FAILED / ALL_DONE) run. Skips (trigger-rule / runIf) are terminal, cascade downstream, and checkpoint
 * nothing.
 */
public final class DagExecutor {

    /** Prefix applied to task names before minting name-based operation IDs. */
    public static final String NODE_PREFIX = "DAG_NODE_T_";

    private DagExecutor() {}

    /**
     * Runs the scheduler to completion (may suspend/replay).
     *
     * @param tasks the registered tasks, in registration order (already validated)
     * @param childCtx the DAG child context to launch operations in
     * @param config the DAG configuration
     * @return the terminal outcome
     */
    public static DagExecutionOutcome run(
            List<TaskHandleImpl<?>> tasks, DurableContextImpl childCtx, DagConfig config) {

        int maxConcurrency = config.maxConcurrency().orElse(Integer.MAX_VALUE);
        TriggerRule defaultRule = config.defaultTriggerRule().orElse(TriggerRule.ALL_SUCCESS);
        Optional<CompletionConfig> completion = config.completionConfig().map(DagExecutor::unwrap);
        int totalTaskCount = tasks.size();

        Map<String, TaskExecution<?>> results = new LinkedHashMap<>();
        // name -> (task, future); insertion order = launch order
        LinkedHashMap<String, InFlight> inFlight = new LinkedHashMap<>();

        DagCompletionReason earlyReason = null;

        fillReady(tasks, childCtx, config, defaultRule, maxConcurrency, results, inFlight);

        while (!inFlight.isEmpty()) {
            var it = inFlight.entrySet().iterator();
            var entry = it.next();
            it.remove();
            String name = entry.getKey();
            try {
                Object result = entry.getValue().future.get();
                results.put(name, succeeded(name, result));
            } catch (SuspendExecutionException suspend) {
                throw suspend; // control-flow: must propagate for suspend/replay
            } catch (Throwable e) {
                results.put(name, failed(name, DagTaskError.of(e)));
            }

            var reason = evaluateEarlyCompletion(completion, results, totalTaskCount);
            if (reason != null) {
                earlyReason = reason;
                break;
            }
            fillReady(tasks, childCtx, config, defaultRule, maxConcurrency, results, inFlight);
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
        return new DagExecutionOutcome(ordered, completionReason);
    }

    /** Launches/skips every currently-ready task, up to the concurrency cap. Idempotent within a wave. */
    private static void fillReady(
            List<TaskHandleImpl<?>> tasks,
            DurableContextImpl childCtx,
            DagConfig config,
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
                if (!rule.eval(statuses)) {
                    results.put(name, skipped(name, SkipReason.TRIGGER_RULE));
                    changed = true;
                    continue;
                }
                Deps deps = new DepsImpl(task.inlineDeps(), results);
                if (task.runIfOpt().isPresent() && !task.runIfOpt().get().test(deps)) {
                    results.put(name, skipped(name, SkipReason.RUN_IF_PREDICATE));
                    changed = true;
                    continue;
                }
                if (inFlight.size() < maxConcurrency) {
                    String id = childCtx.operationIdForName(NODE_PREFIX + name);
                    DurableFuture<?> future = launch(task, childCtx, deps, id);
                    inFlight.put(name, new InFlight(future));
                    changed = true;
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DurableFuture<?> launch(TaskHandleImpl<?> task, DurableContextImpl ctx, Deps deps, String id) {
        TaskExecutor executor = task.executor();
        return executor.launch(ctx, deps, id);
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
            Optional<CompletionConfig> completion, Map<String, TaskExecution<?>> results, int totalTaskCount) {
        if (completion.isEmpty()) {
            return null; // default: drain the whole reachable graph
        }
        var cc = completion.get();
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
