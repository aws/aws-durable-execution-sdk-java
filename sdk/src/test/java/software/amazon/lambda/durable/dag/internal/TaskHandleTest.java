// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.dag.Deps;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.dag.TriggerRule;

class TaskHandleTest {

    private static TaskHandleImpl<String> handle(String name) {
        return new TaskHandleImpl<>(name, TaskKind.STEP, null, null);
    }

    @Test
    void buildersMutateHandleState() {
        var a = handle("a");
        var b = handle("b");
        var c = handle("c");
        c.reads(a).after(b).triggerRule(TriggerRule.ALL_DONE).runIf(deps -> true);

        assertEquals(1, c.inlineDeps().size());
        assertTrue(c.inlineDeps().contains(a));
        assertEquals(2, c.allDeps().size()); // inline (a) + ordering-only (b)
        assertTrue(c.allDeps().contains(a));
        assertTrue(c.allDeps().contains(b));
        assertEquals(Optional.of(TriggerRule.ALL_DONE), c.triggerRuleOpt());
        assertTrue(c.runIfOpt().isPresent());
    }

    @Test
    void depsGetReturnsTypedResultForDeclaredInlineDep() {
        var a = handle("a");
        var b = handle("b");
        b.reads(a);

        Map<String, TaskExecution<?>> results = new LinkedHashMap<>();
        results.put(
                "a",
                new TaskExecution<>(
                        "a",
                        TaskStatus.SUCCEEDED,
                        Optional.empty(),
                        Optional.of("hello"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));

        Deps deps = new DepsImpl(b.inlineDeps(), results);
        assertEquals(Optional.of("hello"), deps.get(a));
    }

    @Test
    void depsGetOnUndeclaredHandleThrows() {
        var a = handle("a");
        var b = handle("b"); // b does NOT read a
        Deps deps = new DepsImpl(b.inlineDeps(), new LinkedHashMap<>());
        assertThrows(IllegalStateException.class, () -> deps.get(a));
    }

    @Test
    void depsGetReturnsEmptyForNonSucceededUpstream() {
        var a = handle("a");
        var b = handle("b");
        b.reads(a);
        Map<String, TaskExecution<?>> results = new LinkedHashMap<>();
        results.put(
                "a",
                new TaskExecution<>(
                        "a",
                        TaskStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        Deps deps = new DepsImpl(b.inlineDeps(), results);
        assertTrue(deps.get(a).isEmpty());
    }

    /**
     * Models the compensation-task scenario the {@code Optional<T>} return type exists for: a downstream task with a
     * non-ALL_SUCCESS trigger rule (here {@code ALL_DONE}) runs even though its inline dependency FAILED. Reading that
     * dependency's result via {@code deps.get(handle)} must yield {@link Optional#empty()}, not a null masquerading as
     * a present value.
     */
    @Test
    void depsGetReturnsEmptyForFailedUpstreamUnderAllDoneTriggerRule() {
        var upstream = handle("upstream"); // designed to fail
        var compensate = handle("compensate");
        compensate.reads(upstream).triggerRule(TriggerRule.ALL_DONE);

        // Snapshot the scheduler would hand to the compensation task: upstream terminal but FAILED.
        Map<String, TaskExecution<?>> results = new LinkedHashMap<>();
        results.put(
                "upstream",
                new TaskExecution<>(
                        "upstream",
                        TaskStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));

        Deps deps = new DepsImpl(compensate.inlineDeps(), results);

        Optional<String> result = deps.get(upstream);
        assertTrue(result.isEmpty(), "failed upstream under ALL_DONE must surface as Optional.empty()");
    }
}
