// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.DagExecutionException;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.DagTaskError;
import software.amazon.lambda.durable.dag.SkipReason;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/**
 * Pins the cross-language DAG container envelope produced by the Java SDK, in both the inline (with {@code tasks}) and
 * offloaded (without {@code tasks}) cases, for a three-task DAG with one success, one failure and one skip. Also pins
 * the lowercase {@code resultKind} vocabulary, the PascalCase error object, and additive-only unknown-field tolerance.
 */
class DagEnvelopeConvergenceTest {

    private static final String OUT_DIR = "/Users/parpooya/workplace/dag-review/java-envelope-out";

    private static final Instant T0 = Instant.parse("2026-07-26T03:19:01.884Z");
    private static final Instant T1 = Instant.parse("2026-07-26T03:19:01.885Z");
    private static final Instant T2 = Instant.parse("2026-07-26T03:19:02.010Z");
    private static final Instant T3 = Instant.parse("2026-07-26T03:19:02.140Z");

    /** The canonical three-task DAG: load-order SUCCEEDED, charge-card FAILED, ship SKIPPED. */
    private static DagResult threeTaskDag() {
        Map<String, TaskExecution<?>> m = new LinkedHashMap<>();
        m.put(
                "load-order",
                new TaskExecution<>(
                        "load-order",
                        TaskStatus.SUCCEEDED,
                        Optional.empty(),
                        Optional.of("order-42"),
                        Optional.empty(),
                        Optional.of(T0),
                        Optional.of(T1)));
        m.put(
                "charge-card",
                new TaskExecution<>(
                        "charge-card",
                        TaskStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new DagTaskError(
                                "java.lang.RuntimeException",
                                "card declined",
                                List.of(
                                        "com.example.Orders.charge(Orders.java:42)",
                                        "com.example.Orders.run(Orders.java:17)"))),
                        Optional.of(T2),
                        Optional.of(T3)));
        m.put(
                "ship",
                new TaskExecution<>(
                        "ship",
                        TaskStatus.SKIPPED,
                        Optional.of(SkipReason.TRIGGER_RULE),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        return new DagResultImpl(m, DagCompletionReason.COMPLETED_WITH_FAILURES, 3, List.of());
    }

    @Test
    void inlineEnvelopePinsExactShape() throws Exception {
        var serdes = new DagResultSerDes(new JacksonSerDes());
        String json = serdes.serialize(threeTaskDag());
        writeArtifact("inline.json", json);

        var root = new ObjectMapper().readTree(json);
        assertEquals("DagResult", root.get("type").asText());
        assertEquals(3, root.get("totalCount").asInt());
        assertEquals(1, root.get("successCount").asInt());
        assertEquals(1, root.get("failureCount").asInt());
        assertEquals(1, root.get("skippedCount").asInt());
        assertEquals("COMPLETED_WITH_FAILURES", root.get("completionReason").asText());
        assertTrue(root.get("startedTaskNames").isArray()
                && root.get("startedTaskNames").isEmpty());
        assertEquals(1, root.get("failedTaskNames").size());
        assertEquals("charge-card", root.get("failedTaskNames").get(0).asText());
        assertTrue(root.has("tasks"), "inline envelope must carry tasks");
        assertEquals(3, root.get("tasks").size());

        var load = root.get("tasks").get(0);
        assertEquals("load-order", load.get("name").asText());
        assertEquals("SUCCEEDED", load.get("status").asText());
        assertTrue(load.get("skipReason").isNull());
        assertEquals("plain", load.get("resultKind").asText());
        assertEquals("order-42", load.get("result").asText());
        assertTrue(load.get("error").isNull());
        assertEquals("2026-07-26T03:19:01.884Z", load.get("startedAt").asText());
        assertEquals("2026-07-26T03:19:01.885Z", load.get("completedAt").asText());

        var charge = root.get("tasks").get(1);
        assertEquals("charge-card", charge.get("name").asText());
        assertEquals("FAILED", charge.get("status").asText());
        // resultKind is null when there is no result to interpret, and the KEY is still
        // present: rule 1 says absent values are explicit null, never omitted.
        assertTrue(charge.has("resultKind"), "resultKind key must be present");
        assertTrue(charge.get("resultKind").isNull(), "FAILED task carries resultKind null");
        assertTrue(charge.has("result"));
        assertTrue(charge.get("result").isNull());
        // Error object is PascalCase.
        var err = charge.get("error");
        assertEquals("java.lang.RuntimeException", err.get("ErrorType").asText());
        assertEquals("card declined", err.get("ErrorMessage").asText());
        assertEquals(2, err.get("StackTrace").size());

        var ship = root.get("tasks").get(2);
        assertEquals("SKIPPED", ship.get("status").asText());
        assertEquals("TRIGGER_RULE", ship.get("skipReason").asText());
        assertTrue(ship.has("resultKind"), "resultKind key must be present");
        assertTrue(ship.get("resultKind").isNull(), "SKIPPED task carries resultKind null");
        assertTrue(ship.has("error"));
        assertTrue(ship.get("error").isNull());
        assertTrue(ship.get("startedAt").isNull());
        assertTrue(ship.get("completedAt").isNull());
    }

    @Test
    void offloadedEnvelopeDropsTasksButKeepsAggregate() throws Exception {
        var serdes = new DagResultSerDes(new JacksonSerDes());
        List<String> ladder = serdes.offloadPayloads(threeTaskDag());
        // Step 2: tasks dropped, failedTaskNames kept.
        String offloaded = ladder.get(0);
        writeArtifact("offloaded.json", offloaded);
        var root = new ObjectMapper().readTree(offloaded);
        assertFalse(root.has("tasks"), "offloaded envelope must NOT carry tasks (absence is the signal)");
        assertEquals("DagResult", root.get("type").asText());
        assertEquals(3, root.get("totalCount").asInt());
        assertEquals(1, root.get("successCount").asInt());
        assertEquals(1, root.get("failureCount").asInt());
        assertEquals(1, root.get("skippedCount").asInt());
        assertEquals("COMPLETED_WITH_FAILURES", root.get("completionReason").asText());
        assertTrue(root.get("startedTaskNames").isArray());
        assertEquals("charge-card", root.get("failedTaskNames").get(0).asText());

        // Step 3 (last resort): failedTaskNames also dropped; counts/reason/startedTaskNames still present.
        var root2 = new ObjectMapper().readTree(ladder.get(1));
        assertFalse(root2.has("tasks"));
        assertFalse(root2.has("failedTaskNames"));
        assertTrue(root2.has("totalCount"));
        assertTrue(root2.has("completionReason"));
        assertTrue(root2.has("startedTaskNames"));
    }

    @Test
    void restoringOffloadedEnvelopeWithFailuresPreservesCountsAndReason() {
        // Contract rule 1: restoring a tasks-less (offloaded) envelope MUST preserve totalCount, the three counts and
        // completionReason. It must NEVER fabricate ALL_COMPLETED / zeroed counts when the envelope says otherwise.
        var serdes = new DagResultSerDes(new JacksonSerDes());
        // Step-2 offload payload: tasks dropped, failedTaskNames kept.
        String offloaded = serdes.offloadPayloads(threeTaskDag()).get(0);

        DagResult restored = serdes.deserialize(offloaded, TypeToken.get(DagResult.class));

        // The per-task map is legitimately empty (tasks were offloaded)...
        assertTrue(restored.results().isEmpty(), "offloaded restore has no per-task detail in the map");
        // ...but the aggregate is honest, NOT a fabricated empty success.
        assertEquals(DagCompletionReason.COMPLETED_WITH_FAILURES, restored.completionReason());
        assertFalse(
                restored.completionReason() == DagCompletionReason.ALL_COMPLETED,
                "must not report ALL_COMPLETED for a DAG that had failures");
        assertEquals(3, restored.totalCount());
        assertEquals(1, restored.successCount());
        assertEquals(1, restored.failureCount());
        assertEquals(1, restored.skippedCount());
        // A caller must never be told the DAG succeeded when the checkpoint says it did not.
        assertThrows(DagExecutionException.class, restored::throwIfError);
    }

    @Test
    void restoringLastResortOffloadEnvelopePreservesCountsAndReason() {
        // The smallest offload candidate additionally drops failedTaskNames; counts, reason and totalCount must still
        // survive the round-trip.
        var serdes = new DagResultSerDes(new JacksonSerDes());
        String lastResort = serdes.offloadPayloads(threeTaskDag()).get(1);

        DagResult restored = serdes.deserialize(lastResort, TypeToken.get(DagResult.class));

        assertEquals(DagCompletionReason.COMPLETED_WITH_FAILURES, restored.completionReason());
        assertEquals(3, restored.totalCount());
        assertEquals(1, restored.successCount());
        assertEquals(1, restored.failureCount());
        assertEquals(1, restored.skippedCount());
    }

    @Test
    void unknownExtraFieldDeserializesWithoutError() {
        // Additive-only evolution (no schemaVersion): a reader MUST ignore an unknown field rather than fail.
        var serdes = new DagResultSerDes(new JacksonSerDes());
        String json = serdes.serialize(threeTaskDag());
        // Inject an unknown top-level field and an unknown per-task field.
        String withExtra = json.replaceFirst("\\{", "{\"futureField\":{\"nested\":true},")
                .replaceFirst("\"name\":\"load-order\"", "\"name\":\"load-order\",\"futureTaskField\":123");

        DagResult restored = serdes.deserialize(withExtra, TypeToken.get(DagResult.class));
        assertEquals(3, restored.totalCount());
        assertEquals(1, restored.successCount());
        assertEquals(1, restored.failureCount());
        assertEquals(1, restored.skippedCount());
        assertEquals(DagCompletionReason.COMPLETED_WITH_FAILURES, restored.completionReason());
        assertEquals("order-42", restored.getResult("load-order").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, restored.getStatus("ship").orElseThrow());
    }

    @Test
    void inlineEnvelopeRoundTrips() {
        var serdes = new DagResultSerDes(new JacksonSerDes());
        var restored = serdes.deserialize(serdes.serialize(threeTaskDag()), TypeToken.get(DagResult.class));
        assertEquals(3, restored.totalCount());
        assertEquals(1, restored.successCount());
        assertEquals(1, restored.failureCount());
        assertEquals(1, restored.skippedCount());
        assertEquals(List.of("charge-card"), restored.failedTaskNames());
        var charge = restored.failed().get(0).error().orElseThrow();
        assertEquals("java.lang.RuntimeException", charge.errorType());
        assertEquals("card declined", charge.errorMessage());
        assertEquals(2, charge.stackTrace().size());
        var load = restored.results().get("load-order");
        assertEquals(Optional.of(T0), load.startedAt());
        assertEquals(Optional.of(T1), load.completedAt());
    }

    private static void writeArtifact(String name, String json) throws Exception {
        // Pretty-print for the human-readable report artifact; assertions above run on the compact production bytes.
        var mapper = new ObjectMapper();
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json));
        Path dir = Path.of(OUT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), pretty + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(name.replace(".json", ".compact.json")), json + "\n", StandardCharsets.UTF_8);
    }
}
