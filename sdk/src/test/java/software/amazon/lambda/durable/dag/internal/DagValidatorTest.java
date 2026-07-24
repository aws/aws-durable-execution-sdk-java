// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.dag.DagCyclicDependencyException;
import software.amazon.lambda.durable.dag.DagDuplicateTaskException;
import software.amazon.lambda.durable.dag.DagInvalidDependencyException;
import software.amazon.lambda.durable.dag.DagInvalidTaskNameException;

class DagValidatorTest {

    private static TaskHandleImpl<Object> task(String name) {
        return new TaskHandleImpl<>(name, TaskKind.STEP, null, null);
    }

    @Test
    void acyclicGraphPasses() {
        var a = task("a");
        var b = task("b");
        var c = task("c").reads(a, b);
        assertDoesNotThrow(() -> DagValidator.validate(List.of(a, b, (TaskHandleImpl<Object>) c)));
    }

    @Test
    void diamondIsNotACycle() {
        var a = task("a");
        var b = task("b").after(a);
        var c = task("c").after(a);
        var d = task("d").after(b, c);
        assertDoesNotThrow(() -> DagValidator.validate(
                List.of(a, (TaskHandleImpl<Object>) b, (TaskHandleImpl<Object>) c, (TaskHandleImpl<Object>) d)));
    }

    @Test
    void selfLoopIsCycle() {
        var a = task("a");
        a.after(a);
        assertThrows(DagCyclicDependencyException.class, () -> DagValidator.validate(List.of(a)));
    }

    @Test
    void twoCycleDetected() {
        var a = task("a");
        var b = task("b");
        a.after(b);
        b.after(a);
        assertThrows(DagCyclicDependencyException.class, () -> DagValidator.validate(List.of(a, b)));
    }

    @Test
    void deepCycleDetected() {
        var a = task("a");
        var b = task("b").after(a);
        var c = task("c").after(b);
        a.after(c);
        assertThrows(
                DagCyclicDependencyException.class,
                () -> DagValidator.validate(List.of(a, (TaskHandleImpl<Object>) b, (TaskHandleImpl<Object>) c)));
    }

    @Test
    void badNameRejected() {
        assertThrows(DagInvalidTaskNameException.class, () -> DagValidator.validate(List.of(task("has-dash"))));
        assertThrows(DagInvalidTaskNameException.class, () -> DagValidator.validate(List.of(task(""))));
        assertThrows(DagInvalidTaskNameException.class, () -> DagValidator.validate(List.of(task("x".repeat(101)))));
        assertThrows(DagInvalidTaskNameException.class, () -> DagValidator.validate(List.of(task("my_DAG_NODE_T_x"))));
    }

    @Test
    void duplicateNamesRejected() {
        assertThrows(DagDuplicateTaskException.class, () -> DagValidator.validate(List.of(task("dup"), task("dup"))));
    }

    @Test
    void foreignDependencyRejected() {
        var registered = task("a");
        var foreign = task("foreign");
        var b = task("b").after(foreign);
        assertThrows(
                DagInvalidDependencyException.class,
                () -> DagValidator.validate(List.of(registered, (TaskHandleImpl<Object>) b)));
    }
}
