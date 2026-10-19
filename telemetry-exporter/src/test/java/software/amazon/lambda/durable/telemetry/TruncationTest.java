// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
