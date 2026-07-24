// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void buildersMutateTaskDef() {
        var a = handle("a");
        var b = handle("b");
        var c = handle("c");
        c.reads(a).dependsOn(b).triggerRule(TriggerRule.ALL_DONE).runIf(deps -> true);

        var def = c.toTaskDef();
        assertEquals(1, def.inlineDeps().size());
        assertTrue(def.inlineDeps().contains(a));
        assertEquals(2, def.allDeps().size()); // inline (a) + ordering-only (b)
        assertTrue(def.allDeps().contains(a));
        assertTrue(def.allDeps().contains(b));
        assertEquals(Optional.of(TriggerRule.ALL_DONE), def.triggerRule());
        assertTrue(def.runIf().isPresent());
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
        assertEquals("hello", deps.get(a));
        assertEquals(Optional.of("hello"), deps.getOptional(a));
    }

    @Test
    void depsGetOnUndeclaredHandleThrows() {
        var a = handle("a");
        var b = handle("b"); // b does NOT read a
        Deps deps = new DepsImpl(b.inlineDeps(), new LinkedHashMap<>());
        assertThrows(IllegalStateException.class, () -> deps.get(a));
    }

    @Test
    void depsGetReturnsNullForNonSucceededUpstream() {
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
        assertNull(deps.get(a));
        assertTrue(deps.getOptional(a).isEmpty());
    }
}
