// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.DagExecutionException;
import software.amazon.lambda.durable.dag.DagTaskError;
import software.amazon.lambda.durable.dag.SkipReason;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class DagResultTest {

    private static TaskExecution<Object> ok(String name, Object value) {
        return new TaskExecution<>(
                name,
                TaskStatus.SUCCEEDED,
                Optional.empty(),
                Optional.ofNullable(value),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static TaskExecution<Object> fail(String name, DagTaskError err) {
        return new TaskExecution<>(
                name,
                TaskStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(err),
                Optional.empty(),
                Optional.empty());
    }

    private static TaskExecution<Object> skip(String name) {
        return new TaskExecution<>(
                name,
                TaskStatus.SKIPPED,
                Optional.of(SkipReason.TRIGGER_RULE),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static DagResultImpl sample() {
        Map<String, TaskExecution<?>> m = new LinkedHashMap<>();
        m.put("a", ok("a", "hello"));
        m.put("b", ok("b", 42));
        m.put("c", skip("c"));
        return new DagResultImpl(m, DagCompletionReason.ALL_COMPLETED);
    }

    @Test
    void countsAndAccessors() {
        var r = sample();
        assertEquals(3, r.totalCount());
        assertEquals(2, r.successCount());
        assertEquals(1, r.skippedCount());
        assertEquals(0, r.failureCount());
        assertEquals(Optional.of("hello"), r.getResult("a"));
        assertEquals(Optional.of(TaskStatus.SKIPPED), r.getStatus("c"));
        assertTrue(r.getResult("c").isEmpty());
    }

    @Test
    void throwIfErrorThrowsOnFailure() {
        Map<String, TaskExecution<?>> m = new LinkedHashMap<>();
        m.put("a", ok("a", "x"));
        m.put("bad", fail("bad", DagTaskError.of(new RuntimeException("boom"))));
        var r = new DagResultImpl(m, DagCompletionReason.COMPLETED_WITH_FAILURES);
        assertEquals(1, r.failureCount());
        assertThrows(DagExecutionException.class, r::throwIfError);
    }

    @Test
    void throwIfErrorNoopWhenClean() {
        sample().throwIfError();
    }

    @Test
    void serdeRoundTripPreservesStructure() {
        var serdes = new DagResultSerDes(new JacksonSerDes());
        var original = sample();
        var json = serdes.serialize(original);
        var restored = serdes.deserialize(json, TypeToken.get(software.amazon.lambda.durable.dag.DagResult.class));
        assertEquals(3, restored.totalCount());
        assertEquals(2, restored.successCount());
        assertEquals(1, restored.skippedCount());
        assertEquals(Optional.of("hello"), restored.getResult("a"));
        assertEquals(42, ((Number) restored.getResult("b").orElseThrow()).intValue());
        assertEquals(Optional.of(TaskStatus.SKIPPED), restored.getStatus("c"));
        assertEquals(DagCompletionReason.ALL_COMPLETED, restored.completionReason());
    }

    @Test
    void emptyDagResultHasZeroCounts() {
        var r = new DagResultImpl(new LinkedHashMap<>(), DagCompletionReason.ALL_COMPLETED);
        assertEquals(0, r.totalCount());
        assertEquals(0, r.successCount());
        assertEquals(0, r.failureCount());
        assertEquals(0, r.skippedCount());
        assertTrue(r.getResult("nope").isEmpty());
        assertTrue(r.getStatus("nope").isEmpty());
        r.throwIfError(); // must not throw
    }

    @Test
    void serdeRoundTripRehydratesMapResultTask() {
        var serdes = new DagResultSerDes(new JacksonSerDes());
        var mapResult = new software.amazon.lambda.durable.model.MapResult<>(
                java.util.List.of(
                        software.amazon.lambda.durable.model.MapResult.MapResultItem.succeeded("x"),
                        software.amazon.lambda.durable.model.MapResult.MapResultItem.succeeded("y")),
                software.amazon.lambda.durable.model.ConcurrencyCompletionStatus.ALL_COMPLETED);
        Map<String, TaskExecution<?>> m = new LinkedHashMap<>();
        m.put("m", ok("m", mapResult));
        var original = new DagResultImpl(m, DagCompletionReason.ALL_COMPLETED);

        var restored = serdes.deserialize(
                serdes.serialize(original), TypeToken.get(software.amazon.lambda.durable.dag.DagResult.class));

        var rehydrated = restored.getResult("m").orElseThrow();
        assertTrue(
                rehydrated instanceof software.amazon.lambda.durable.model.MapResult,
                "MAP result must rehydrate to a MapResult instance, was: " + rehydrated.getClass());
        var mr = (software.amazon.lambda.durable.model.MapResult<?>) rehydrated;
        assertEquals(2, mr.size());
        assertEquals("x", mr.getResult(0));
        assertEquals("y", mr.getResult(1));
    }

    @Test
    void serdeRoundTripRehydratesNestedDagResultTask() {
        var serdes = new DagResultSerDes(new JacksonSerDes());
        Map<String, TaskExecution<?>> inner = new LinkedHashMap<>();
        inner.put("leaf", ok("leaf", "deep"));
        var innerDag = new DagResultImpl(inner, DagCompletionReason.ALL_COMPLETED);

        Map<String, TaskExecution<?>> outer = new LinkedHashMap<>();
        outer.put("nested", ok("nested", innerDag));
        var original = new DagResultImpl(outer, DagCompletionReason.ALL_COMPLETED);

        var restored = serdes.deserialize(
                serdes.serialize(original), TypeToken.get(software.amazon.lambda.durable.dag.DagResult.class));

        var rehydrated = restored.getResult("nested").orElseThrow();
        assertTrue(
                rehydrated instanceof software.amazon.lambda.durable.dag.DagResult,
                "DAG result must rehydrate to a DagResult instance, was: " + rehydrated.getClass());
        var nested = (software.amazon.lambda.durable.dag.DagResult) rehydrated;
        assertEquals(1, nested.totalCount());
        assertEquals(Optional.of("deep"), nested.getResult("leaf"));
        assertEquals(DagCompletionReason.ALL_COMPLETED, nested.completionReason());
    }

    @Test
    void serdeRoundTripReconstructsError() {
        var serdes = new DagResultSerDes(new JacksonSerDes());
        Map<String, TaskExecution<?>> m = new LinkedHashMap<>();
        m.put("bad", fail("bad", DagTaskError.of(new IllegalStateException("nope"))));
        var original = new DagResultImpl(m, DagCompletionReason.COMPLETED_WITH_FAILURES);
        var restored = serdes.deserialize(
                serdes.serialize(original), TypeToken.get(software.amazon.lambda.durable.dag.DagResult.class));
        assertEquals(1, restored.failureCount());
        var err = restored.failed().get(0).error().orElseThrow();
        assertEquals(IllegalStateException.class.getName(), err.errorType());
        assertEquals("nope", err.errorMessage());
        assertFalse(err.cause().isPresent()); // cause is not serialized
    }
}
