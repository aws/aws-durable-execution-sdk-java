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
