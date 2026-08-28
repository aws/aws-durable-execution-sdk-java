// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationsIndexTest {

    @Test
    void singleOccurrenceKeepsResultAndError() {
        OperationRecord op = new OperationRecord()
                .id("a")
                .name("compute")
                .type("STEP")
                .subType("Step")
                .status("SUCCEEDED")
                .attempt(1)
                .result(42);
        Map<String, OperationSummary> byName = OperationsIndex.buildOperationsByName(List.of(op));
        OperationSummary s = byName.get("compute");
        assertEquals(1, s.count);
        assertEquals(0, s.failedCount);
        assertEquals("STEP", s.type);
        assertEquals(42, s.result);
    }

    @Test
    void repeatedNameAggregatesAndDropsResult() {
        OperationRecord a = new OperationRecord()
                .id("1")
                .name("task")
                .type("STEP")
                .subType("Step")
                .status("SUCCEEDED");
        OperationRecord b = new OperationRecord()
                .id("2")
                .name("task")
                .type("STEP")
                .subType("Step")
                .status("SUCCEEDED");
        OperationRecord c = new OperationRecord()
                .id("3")
                .name("task")
                .type("STEP")
                .subType("Step")
                .status("SUCCEEDED");
        Map<String, OperationSummary> byName = OperationsIndex.buildOperationsByName(List.of(a, b, c));
        OperationSummary s = byName.get("task");
        assertEquals(3, s.count);
        assertEquals(0, s.failedCount);
        assertNull(s.result);
        assertNull(s.error);
    }

    @Test
    void maxAttemptReflectsHighestAttempt() {
        OperationRecord retried = new OperationRecord()
                .id("1")
                .name("retried-step")
                .type("STEP")
                .subType("Step")
                .status("SUCCEEDED")
                .attempt(2);
        Map<String, OperationSummary> byName = OperationsIndex.buildOperationsByName(List.of(retried));
        assertEquals(Integer.valueOf(2), byName.get("retried-step").maxAttempt);
        assertEquals(1, byName.get("retried-step").count);
    }

    @Test
    void unnamedOperationsAreSkipped() {
        OperationRecord named =
                new OperationRecord().id("1").name("named").type("STEP").status("SUCCEEDED");
        OperationRecord unnamed = new OperationRecord().id("2").type("STEP").status("SUCCEEDED");
        Map<String, OperationSummary> byName = OperationsIndex.buildOperationsByName(List.of(named, unnamed));
        assertTrue(byName.containsKey("named"));
        assertEquals(1, byName.size());
    }

    @Test
    void failedOccurrenceCountsAsFailed() {
        OperationRecord failed = new OperationRecord()
                .id("1")
                .name("failing")
                .type("STEP")
                .subType("Step")
                .status("FAILED");
        Map<String, OperationSummary> byName = OperationsIndex.buildOperationsByName(List.of(failed));
        assertEquals(1, byName.get("failing").failedCount);
        assertFalse(byName.get("failing").count == 0);
    }
}
