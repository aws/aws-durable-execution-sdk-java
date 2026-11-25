// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.telemetry.exporters.LambdaLogExporter;
import software.amazon.lambda.durable.telemetry.exporters.OperationsFormat;

class TruncationTest {

    private DurableExecutionTelemetryRecord recordWithResults(int bytesEach) {
        DurableExecutionTelemetryRecord r = new DurableExecutionTelemetryRecord();
        r.executionArn = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/e/i";
        r.status = "SUCCEEDED";
        r.startTime = "2026-08-05T00:00:00Z";
        String big = "x".repeat(bytesEach);
        r.operations.add(new OperationRecord()
                .id("1")
                .name("bulk-1")
                .type("STEP")
                .status("SUCCEEDED")
                .startTime("2026-08-05T00:00:01Z")
                .result(big));
        r.operations.add(new OperationRecord()
                .id("2")
                .name("bulk-2")
                .type("STEP")
                .status("SUCCEEDED")
                .startTime("2026-08-05T00:00:02Z")
                .result(big));
        r.operations.add(new OperationRecord()
                .id("3")
                .name("bulk-3")
                .type("STEP")
                .status("SUCCEEDED")
                .startTime("2026-08-05T00:00:03Z")
                .result(big));
        return r;
    }

    @Test
    void phase1DropsResultsOldestFirstAndKeepsNewest() {
        DurableExecutionTelemetryRecord r = recordWithResults(2000);
        // Big enough for all three result-stripped ops, too small for the ~2 KB results.
        DurableExecutionTelemetryRecord out =
                Truncation.truncateRecord(r, 4096, DurableExecutionTelemetryRecord::toWireMap);
        assertEquals(Boolean.TRUE, out.truncated);
        assertEquals(3, out.operations().size());
        assertNull(out.operations().get(0).result(), "oldest result dropped");
        assertEquals(Boolean.TRUE, out.operations().get(0).truncated());
        assertTrue(out.operations().get(2).result() != null, "newest result retained");
        assertNull(out.droppedOperations, "no whole operation dropped in phase 1");
    }

    @Test
    void phase2DropsWholeOperationsOldestFirst() {
        // No results to drop, tiny limit → whole operations dropped oldest-first.
        DurableExecutionTelemetryRecord r = new DurableExecutionTelemetryRecord();
        r.executionArn = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/e/i";
        r.status = "SUCCEEDED";
        r.startTime = "2026-08-05T00:00:00Z";
        for (int i = 1; i <= 3; i++) {
            r.operations.add(new OperationRecord()
                    .id(String.valueOf(i))
                    .name("bulk-" + i)
                    .type("STEP")
                    .status("SUCCEEDED")
                    .startTime("2026-08-05T00:00:0" + i + "Z"));
        }
        int base = Json.byteSize(r.toWireMap());
        // Force at least one whole-operation drop.
        DurableExecutionTelemetryRecord out =
                Truncation.truncateRecord(r, base - 60, DurableExecutionTelemetryRecord::toWireMap);
        assertEquals(Boolean.TRUE, out.truncated);
        assertTrue(out.droppedOperations != null && out.droppedOperations >= 1);
        boolean bulk1Present = out.operations().stream().anyMatch(o -> "bulk-1".equals(o.name()));
        boolean bulk3Present = out.operations().stream().anyMatch(o -> "bulk-3".equals(o.name()));
        assertTrue(!bulk1Present, "oldest operation dropped first");
        assertTrue(bulk3Present, "newest operation retained");
    }

    @Test
    void noTruncationWhenUnderLimit() {
        DurableExecutionTelemetryRecord r = recordWithResults(10);
        DurableExecutionTelemetryRecord out =
                Truncation.truncateRecord(r, 5_000_000, DurableExecutionTelemetryRecord::toWireMap);
        assertNull(out.truncated);
    }

    /**
     * Truncating an already-truncated record adds to its {@code droppedOperations} and keeps its other markers, so a
     * second pass can never under-report what was dropped.
     */
    @Test
    void truncatingATruncatedRecordKeepsItsMarkers() {
        DurableExecutionTelemetryRecord r = RecordFactory.sampleWithFailedOperations(40, "m".repeat(200));
        r.input = "i".repeat(5_000);
        int full = Json.byteSize(r.toWireMap());
        DurableExecutionTelemetryRecord first =
                Truncation.truncateRecord(r, full / 2, DurableExecutionTelemetryRecord::toWireMap);
        int firstDropped = first.droppedOperations;
        assertTrue(firstDropped > 0, "first pass drops operations");
        assertEquals(Boolean.TRUE, first.droppedCauses);

        DurableExecutionTelemetryRecord second =
                Truncation.truncateRecord(first, full / 4, DurableExecutionTelemetryRecord::toWireMap);
        assertEquals(40, second.operations().size() + second.droppedOperations, "every dropped operation is counted");
        assertTrue(second.droppedOperations > firstDropped);
        assertEquals(Boolean.TRUE, second.droppedCauses, "kept from the first pass");
        assertEquals(Boolean.TRUE, second.truncated);

        // A pass that has nothing more to drop returns the record unchanged.
        assertSame(second, Truncation.truncateRecord(second, full, DurableExecutionTelemetryRecord::toWireMap));
    }

    /** One pass reports every operation it dropped. */
    @Test
    void droppedOperationsCountsEveryDroppedOperation() {
        DurableExecutionTelemetryRecord r = RecordFactory.sampleWithFailedOperations(400, "\"".repeat(200));
        DurableExecutionTelemetryRecord out =
                Truncation.truncateRecord(r, 100_000, DurableExecutionTelemetryRecord::toWireMap);
        assertTrue(out.droppedOperations > 0);
        assertEquals(400, out.operations().size() + out.droppedOperations);
        assertTrue(Json.byteSize(out.toWireMap()) <= 100_000);
    }

    /** {@code n} successful steps with distinct names and no results, one second apart. */
    static DurableExecutionTelemetryRecord namedSteps(int n) {
        DurableExecutionTelemetryRecord r = new DurableExecutionTelemetryRecord();
        r.executionArn = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/e/i";
        r.status = "SUCCEEDED";
        r.startTime = "2026-08-05T00:00:00Z";
        Instant start = Instant.parse("2026-08-05T00:00:00Z");
        for (int i = 0; i < n; i++) {
            r.operations.add(new OperationRecord()
                    .id("op-" + i)
                    .name("step-" + i)
                    .type("STEP")
                    .status("SUCCEEDED")
                    .startTime(start.plusSeconds(i).toString())
                    .durationMs(3L)
                    .attempt(1));
        }
        return r;
    }

    /**
     * Issue #877: 2,000 named steps in the by-name format, over the 256,000-byte limit. The pass renders the record a
     * logarithmic number of times, not once per dropped operation.
     */
    @Test
    void renderCountGrowsWithTheLogOfTheOperationCount() {
        DurableExecutionTelemetryRecord r = namedSteps(2_000);
        assertTrue(Json.byteSize(r.toByNameWireMap()) > 256_000, "the record is over the limit");
        AtomicInteger renders = new AtomicInteger();
        DurableExecutionTelemetryRecord out = Truncation.truncateRecord(r, 256_000, rec -> {
            renders.incrementAndGet();
            return rec.toByNameWireMap();
        });
        assertTrue(renders.get() < 40, renders + " renders");
        assertTrue(Json.byteSize(out.toByNameWireMap()) <= 256_000);
        assertEquals(2_000, out.operations().size() + out.droppedOperations);
    }

    /** A custom exporter's {@code render} gets the same bounded number of calls through the export path. */
    @Test
    void customRenderGetsABoundedRenderCount() {
        AtomicInteger renders = new AtomicInteger();
        AtomicInteger exportedOperations = new AtomicInteger(-1);
        TelemetryExporter custom = new TelemetryExporter() {
            @Override
            public void export(DurableExecutionTelemetryRecord record) {
                exportedOperations.set(record.operations().size() + record.droppedOperations);
            }

            @Override
            public Integer maxRecordSizeBytes() {
                return 256_000;
            }

            @Override
            public Object render(DurableExecutionTelemetryRecord record) {
                renders.incrementAndGet();
                return Map.of("custom", record.toByNameWireMap());
            }
        };
        DurableExecutionTelemetry.exportRecord(namedSteps(4_000), custom);
        assertTrue(renders.get() < 40, renders + " renders");
        assertEquals(4_000, exportedOperations.get());
    }

    /** Results whose JSON is 7 bytes or shorter, so clearing them would make the record bigger. */
    private static final Object[] SMALL_RESULTS = {true, false, 1, 0, 42, "", "ok", "abcde", 3.5};

    /** Whether the pass may clear {@code result}: its JSON is longer than 7 bytes. */
    private static boolean clearable(Object result) {
        return result != null && Json.byteSize(result) > 7;
    }

    /** A random record with quotes, backslashes, non-ASCII text, repeated and empty names, results and errors. */
    private static DurableExecutionTelemetryRecord randomRecord(Random random, int n) {
        String[] texts = {"", "a", "\"", "\\", "é", "😀", "\u2028", "\u0001", "x\"y\\z"};
        DurableExecutionTelemetryRecord r = RecordFactory.sample();
        r.operations.clear();
        r.input = random.nextBoolean() ? null : texts[random.nextInt(texts.length)].repeat(random.nextInt(2_000));
        r.output = random.nextBoolean() ? null : Map.of("k", texts[random.nextInt(texts.length)].repeat(500));
        Instant start = Instant.parse("2026-08-05T00:00:00Z");
        for (int i = 0; i < n; i++) {
            String name = random.nextInt(4) == 0 ? "" : "n" + random.nextInt(Math.max(1, n / 3));
            OperationRecord op = new OperationRecord()
                    .id("id-" + i)
                    .name(name + texts[random.nextInt(texts.length)])
                    .type("STEP")
                    .status("SUCCEEDED")
                    .startTime(start.plusSeconds(i).toString())
                    .durationMs((long) random.nextInt(10_000))
                    .attempt(1 + random.nextInt(3));
            int resultKind = random.nextInt(4);
            if (resultKind == 0) {
                op.result(SMALL_RESULTS[random.nextInt(SMALL_RESULTS.length)]);
            } else if (resultKind == 1) {
                op.result(texts[random.nextInt(texts.length)].repeat(random.nextInt(300)));
            }
            if (random.nextInt(4) == 0) {
                op.status("FAILED")
                        .error(new ErrorInfo(
                                "E" + i, texts[random.nextInt(texts.length)].repeat(50), new ErrorInfo("C", "c")));
            }
            r.addOperation(op);
        }
        return r;
    }

    /**
     * For random records, limits and renderings, the output never goes over the limit unless everything droppable is
     * dropped, the pass renders a logarithmic number of times, and results and operations are dropped oldest first.
     */
    @Test
    void randomRecordsStayWithinTheLimitAndDropOldestFirst() {
        Random random = new Random(877);
        List<Function<DurableExecutionTelemetryRecord, Object>> renders = List.of(
                DurableExecutionTelemetryRecord::toWireMap,
                DurableExecutionTelemetryRecord::toByNameWireMap,
                OperationsFormat.BOTH::apply,
                new LambdaLogExporter()::render);
        for (int c = 0; c < 300; c++) {
            int n = 1 + random.nextInt(120);
            DurableExecutionTelemetryRecord r = randomRecord(random, n);
            Function<DurableExecutionTelemetryRecord, Object> render = renders.get(c % renders.size());
            int full = Json.byteSize(render.apply(r));
            int limit = 200 + random.nextInt(full);
            AtomicInteger count = new AtomicInteger();
            DurableExecutionTelemetryRecord out = Truncation.truncateRecord(r.deepCopy(), limit, rec -> {
                count.incrementAndGet();
                return render.apply(rec);
            });
            String label = "case " + c + ", limit " + limit;
            boolean everythingDropped = out.operations().isEmpty() && out.input == null && out.output == null;
            assertTrue(Json.byteSize(render.apply(out)) <= limit || everythingDropped, label);
            int log = 32 - Integer.numberOfLeadingZeros(n + 1);
            assertTrue(count.get() <= 2 * log + 5, label + ": " + count + " renders");

            int droppedOperations = out.droppedOperations == null ? 0 : out.droppedOperations;
            assertEquals(n, out.operations().size() + droppedOperations, label);
            for (int i = 0; i < out.operations().size(); i++) {
                assertEquals(
                        "id-" + (droppedOperations + i), out.operations().get(i).id(), label + ": newest kept");
            }
            boolean resultKept = false;
            for (OperationRecord op : out.operations()) {
                resultKept |= clearable(op.result());
                assertTrue(!resultKept || !Boolean.TRUE.equals(op.truncated()), label + ": oldest results first");
                assertTrue(!Boolean.TRUE.equals(op.truncated()) || op.result() == null, label + ": cleared result");
            }
        }
    }

    /**
     * The one-at-a-time pass from before the binary search: the first record, in drop order, that fits. It clears only
     * results whose JSON is longer than 7 bytes, as the pass does. Records here have no causes, and every operation has
     * a later start time than the one before it. With {@code clearSmall} it clears every result, as the pass does in a
     * rendering without per-operation markers.
     */
    private static DurableExecutionTelemetryRecord oneAtATime(
            DurableExecutionTelemetryRecord r,
            int limit,
            Function<DurableExecutionTelemetryRecord, Object> render,
            boolean clearSmall) {
        List<OperationRecord> ops = new ArrayList<>();
        r.operations().forEach(op -> ops.add(op.copy()));
        for (int j = 0; j <= ops.size(); j++) {
            Object result = j > 0 ? ops.get(j - 1).result() : null;
            if (result != null && (clearSmall || clearable(result))) {
                ops.set(j - 1, ops.get(j - 1).copy().result(null).truncated(true));
            }
            DurableExecutionTelemetryRecord candidate = r.copy();
            candidate.operations = new ArrayList<>(ops);
            candidate.truncated = Boolean.TRUE;
            if (Json.byteSize(render.apply(candidate)) <= limit) {
                return candidate;
            }
        }
        for (int k = 1; k <= ops.size(); k++) {
            DurableExecutionTelemetryRecord candidate = r.copy();
            candidate.operations = new ArrayList<>(ops.subList(k, ops.size()));
            candidate.truncated = Boolean.TRUE;
            candidate.droppedOperations = k;
            if (Json.byteSize(render.apply(candidate)) <= limit) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * In the array format each drop makes the record smaller, so the binary search drops exactly what the one-at-a-time
     * pass drops.
     */
    @Test
    void arrayFormatMatchesTheOneAtATimePass() {
        Random random = new Random(881);
        int compared = 0;
        for (int c = 0; c < 300; c++) {
            int n = 1 + random.nextInt(150);
            DurableExecutionTelemetryRecord r = randomRecord(random, n);
            r.input = null;
            r.output = null;
            r.operations.replaceAll(op -> op.error() == null ? op : op.copy().error(new ErrorInfo("E", "m")));
            int full = Json.byteSize(r.toWireMap());
            int limit = 400 + random.nextInt(full);
            DurableExecutionTelemetryRecord expected =
                    oneAtATime(r, limit, DurableExecutionTelemetryRecord::toWireMap, false);
            if (expected == null || full <= limit) {
                continue;
            }
            compared++;
            DurableExecutionTelemetryRecord out =
                    Truncation.truncateRecord(r.deepCopy(), limit, DurableExecutionTelemetryRecord::toWireMap);
            assertEquals(Json.stringify(expected.toWireMap()), Json.stringify(out.toWireMap()), "case " + c);
        }
        assertTrue(compared > 200, compared + " cases compared");
    }

    /** {@code results.length} steps one second apart; step {@code i} has result {@code results[i]}. */
    private static DurableExecutionTelemetryRecord stepsWithResults(Object... results) {
        DurableExecutionTelemetryRecord r = namedSteps(results.length);
        for (int i = 0; i < results.length; i++) {
            r.operations.get(i).result(results[i]);
        }
        return r;
    }

    private static int truncatedCount(DurableExecutionTelemetryRecord r) {
        return (int) r.operations().stream()
                .filter(op -> Boolean.TRUE.equals(op.truncated()))
                .count();
    }

    /**
     * One large old result, then many small results. Clearing a small result makes the record bigger. So the pass
     * clears only the large result, as the one-at-a-time pass does, and drops no operation.
     */
    @Test
    void smallResultsAreNotClearedAfterALargeOldResult() {
        Object[] small = {true, 1, "ok", ""};
        for (int large : new int[] {40, 5_000}) {
            Object[] results = new Object[2_000];
            results[0] = "x".repeat(large);
            for (int i = 1; i < results.length; i++) {
                results[i] = small[i % small.length];
            }
            DurableExecutionTelemetryRecord r = stepsWithResults(results);
            int limit = Json.byteSize(r.toWireMap()) - 1;
            DurableExecutionTelemetryRecord out =
                    Truncation.truncateRecord(r.deepCopy(), limit, DurableExecutionTelemetryRecord::toWireMap);
            DurableExecutionTelemetryRecord expected =
                    oneAtATime(r, limit, DurableExecutionTelemetryRecord::toWireMap, false);
            assertEquals(Json.stringify(expected.toWireMap()), Json.stringify(out.toWireMap()), "large " + large);
            assertNull(out.droppedOperations, "large " + large);
            assertEquals(1, truncatedCount(out), "large " + large);
            assertNull(out.operations().get(0).result());
            assertEquals("ok", out.operations().get(2).result(), "a small result stays");
        }
    }

    /** The record fits only when every clearable result is cleared. No operation is dropped. */
    @Test
    void clearingEveryClearableResultIsTriedBeforeDroppingOperations() {
        String big = "y".repeat(100);
        DurableExecutionTelemetryRecord r = stepsWithResults(big, true, big, "ok", big, 1, big);
        DurableExecutionTelemetryRecord allCleared = r.copy();
        allCleared.operations = new ArrayList<>();
        for (OperationRecord op : r.operations()) {
            allCleared.operations.add(
                    clearable(op.result()) ? op.copy().result(null).truncated(true) : op);
        }
        allCleared.truncated = Boolean.TRUE;
        int limit = Json.byteSize(allCleared.toWireMap());
        DurableExecutionTelemetryRecord out =
                Truncation.truncateRecord(r.deepCopy(), limit, DurableExecutionTelemetryRecord::toWireMap);
        assertNull(out.droppedOperations);
        assertEquals(4, truncatedCount(out));
        assertEquals(Json.stringify(allCleared.toWireMap()), Json.stringify(out.toWireMap()));
    }

    /** The record fits only when every operation is dropped. The input is kept. */
    @Test
    void droppingEveryOperationIsTriedBeforeDroppingTheInput() {
        DurableExecutionTelemetryRecord r = namedSteps(7);
        r.input = "input";
        DurableExecutionTelemetryRecord noOperations = r.copy();
        noOperations.operations = new ArrayList<>();
        noOperations.truncated = Boolean.TRUE;
        noOperations.droppedOperations = 7;
        int limit = Json.byteSize(noOperations.toWireMap());
        DurableExecutionTelemetryRecord out =
                Truncation.truncateRecord(r.deepCopy(), limit, DurableExecutionTelemetryRecord::toWireMap);
        assertEquals("input", out.input);
        assertNull(out.droppedInput);
        assertEquals(7, out.droppedOperations);
        assertTrue(out.operations().isEmpty());
    }

    /** Dropping the causes alone makes the record fit, at exactly the limit. No result is cleared. */
    @Test
    void droppingCausesAloneClearsNoResult() {
        DurableExecutionTelemetryRecord r = stepsWithResults("z".repeat(500), "z".repeat(500), "z".repeat(500));
        for (OperationRecord op : r.operations()) {
            op.status("FAILED").error(new ErrorInfo("E", "m", new ErrorInfo("Cause", "c".repeat(200))));
        }
        DurableExecutionTelemetryRecord causesDropped = r.copy();
        causesDropped.operations = new ArrayList<>();
        for (OperationRecord op : r.operations()) {
            causesDropped.operations.add(op.copy().error(new ErrorInfo("E", "m")));
        }
        causesDropped.truncated = Boolean.TRUE;
        causesDropped.droppedCauses = Boolean.TRUE;
        int limit = Json.byteSize(causesDropped.toWireMap());
        DurableExecutionTelemetryRecord out =
                Truncation.truncateRecord(r.deepCopy(), limit, DurableExecutionTelemetryRecord::toWireMap);
        assertEquals(Boolean.TRUE, out.droppedCauses);
        assertEquals(0, truncatedCount(out));
        assertEquals(Json.stringify(causesDropped.toWireMap()), Json.stringify(out.toWireMap()));
    }

    /**
     * One large old result, then many small results, in renderings without a per-operation marker. There clearing a
     * small result makes the record smaller. So the pass clears small results too, as the one-at-a-time pass does, and
     * drops no operation.
     */
    @Test
    void byNameClearsSmallResultsBeforeDroppingOperations() {
        Object[] small = {true, 1, "ok", ""};
        Object[] results = new Object[2_000];
        results[0] = "x".repeat(5_000);
        for (int i = 1; i < results.length; i++) {
            results[i] = small[i % small.length];
        }
        DurableExecutionTelemetryRecord r = stepsWithResults(results);
        LambdaLogExporter lambdaLog = new LambdaLogExporter();
        List<Function<DurableExecutionTelemetryRecord, Object>> renders =
                List.of(DurableExecutionTelemetryRecord::toByNameWireMap, lambdaLog::render);
        for (Function<DurableExecutionTelemetryRecord, Object> render : renders) {
            DurableExecutionTelemetryRecord largeCleared = r.copy();
            largeCleared.operations = new ArrayList<>(r.operations());
            largeCleared.operations.set(
                    0, r.operations().get(0).copy().result(null).truncated(true));
            largeCleared.truncated = Boolean.TRUE;
            int limit = Json.byteSize(render.apply(largeCleared)) - 200;
            DurableExecutionTelemetryRecord expected = oneAtATime(r, limit, render, true);
            DurableExecutionTelemetryRecord out = Truncation.truncateRecord(r.deepCopy(), limit, render);
            assertNull(out.droppedOperations);
            assertTrue(truncatedCount(out) > 1, "small results are cleared");
            assertEquals(Json.stringify(expected.toWireMap()), Json.stringify(out.toWireMap()));
        }
    }

    /**
     * Without dropped causes and without results, the record with every result kept is the input with markers added. So
     * the pass measures the input once and then one operation count.
     */
    @Test
    void noRenderForTheUnchangedRecord() {
        DurableExecutionTelemetryRecord r = namedSteps(1);
        AtomicInteger renders = new AtomicInteger();
        DurableExecutionTelemetryRecord out = Truncation.truncateRecord(r, Json.byteSize(r.toWireMap()) - 1, rec -> {
            renders.incrementAndGet();
            return rec.toWireMap();
        });
        assertEquals(1, out.droppedOperations);
        assertEquals(2, renders.get());
    }
}
