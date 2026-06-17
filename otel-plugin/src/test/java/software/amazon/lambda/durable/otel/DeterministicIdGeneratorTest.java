// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeterministicIdGeneratorTest {

    private DeterministicIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new DeterministicIdGenerator();
    }

    @Test
    void generateTraceId_withoutArn_returnsRandom() {
        var id1 = generator.generateTraceId();
        var id2 = generator.generateTraceId();

        assertNotNull(id1);
        assertEquals(32, id1.length());
        // Random IDs should differ (extremely unlikely to collide)
        assertNotEquals(id1, id2);
    }

    @Test
    void generateTraceId_withArn_returnsDeterministic() {
        generator.setDurableExecutionArn("arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1");

        var id1 = generator.generateTraceId();
        var id2 = generator.generateTraceId();

        assertEquals(32, id1.length());
        assertEquals(id1, id2, "Same ARN should always produce same trace ID");
    }

    @Test
    void generateTraceId_differentArns_produceDifferentIds() {
        generator.setDurableExecutionArn("arn:exec1");
        var id1 = generator.generateTraceId();

        generator.setDurableExecutionArn("arn:exec2");
        var id2 = generator.generateTraceId();

        assertNotEquals(id1, id2);
    }

    @Test
    void generateSpanId_withoutOperationId_returnsRandom() {
        var id1 = generator.generateSpanId();
        var id2 = generator.generateSpanId();

        assertEquals(16, id1.length());
        assertNotEquals(id1, id2);
    }

    @Test
    void generateSpanId_withOperationId_returnsDeterministic() {
        generator.setDurableExecutionArn("arn:exec1");
        generator.setNextSpanOperationId("op-hash-1");
        var id1 = generator.generateSpanId();

        generator.setNextSpanOperationId("op-hash-1");
        var id2 = generator.generateSpanId();

        assertEquals(16, id1.length());
        assertEquals(id1, id2, "Same operation ID should produce same span ID");
    }

    @Test
    void generateSpanId_differentOperationIds_produceDifferentIds() {
        generator.setDurableExecutionArn("arn:exec1");

        generator.setNextSpanOperationId("op-1");
        var id1 = generator.generateSpanId();

        generator.setNextSpanOperationId("op-2");
        var id2 = generator.generateSpanId();

        assertNotEquals(id1, id2);
    }

    @Test
    void generateSpanId_consumesPendingId() {
        generator.setNextSpanOperationId("op-1");
        var deterministic = generator.generateSpanId();

        // Second call should be random (pending was consumed)
        var random = generator.generateSpanId();
        assertNotEquals(deterministic, random);
    }

    @Test
    void generateSpanIdForOperation_doesNotConsumePending() {
        generator.setDurableExecutionArn("arn:exec1");
        generator.setNextSpanOperationId("op-1");

        // This should NOT consume the pending
        var forOperation = generator.generateSpanIdForOperation("op-2");

        // The pending should still be consumed by generateSpanId
        var fromPending = generator.generateSpanId();

        assertEquals(16, forOperation.length());
        assertEquals(16, fromPending.length());
        assertNotEquals(forOperation, fromPending);
    }

    @Test
    void generateSpanIdForOperation_isDeterministic() {
        generator.setDurableExecutionArn("arn:exec1");

        var id1 = generator.generateSpanIdForOperation("op-1");
        var id2 = generator.generateSpanIdForOperation("op-1");

        assertEquals(id1, id2);
    }

    @Test
    void traceId_isValidHex() {
        generator.setDurableExecutionArn("arn:exec1");
        var traceId = generator.generateTraceId();

        assertTrue(traceId.matches("[0-9a-f]{32}"), "Trace ID should be 32 hex chars: " + traceId);
    }

    @Test
    void spanId_isValidHex() {
        generator.setDurableExecutionArn("arn:exec1");
        generator.setNextSpanOperationId("op-1");
        var spanId = generator.generateSpanId();

        assertTrue(spanId.matches("[0-9a-f]{16}"), "Span ID should be 16 hex chars: " + spanId);
    }

    // ─── X-Ray extracted trace ID priority tests ─────────────────────────

    @Test
    void generateTraceId_extractedTakesPriorityOverArn() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        generator.setDurableExecutionArn("arn:aws:lambda:us-east-1:123:function:test");
        generator.setExtractedTraceId(xrayTraceId);

        var result = generator.generateTraceId();

        assertEquals(xrayTraceId, result, "Extracted X-Ray trace ID should take priority over ARN-derived");
    }

    @Test
    void generateTraceId_extractedIdReturnedConsistently() {
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        generator.setExtractedTraceId(xrayTraceId);

        // Should return the same value on every call
        assertEquals(xrayTraceId, generator.generateTraceId());
        assertEquals(xrayTraceId, generator.generateTraceId());
        assertEquals(xrayTraceId, generator.generateTraceId());
    }

    @Test
    void generateTraceId_extractedIdOverridesArnDerived() {
        generator.setDurableExecutionArn("arn:exec1");
        var arnDerived = generator.generateTraceId();

        // Now set an extracted ID — it should override
        var xrayTraceId = "1234567890abcdef1234567890abcdef";
        generator.setExtractedTraceId(xrayTraceId);

        var afterExtracted = generator.generateTraceId();
        assertEquals(xrayTraceId, afterExtracted);
        assertNotEquals(arnDerived, afterExtracted, "Extracted should differ from ARN-derived");
    }

    @Test
    void generateTraceId_priorityOrder_extractedFirst() {
        // Priority 1: extracted > Priority 2: ARN-derived > Priority 3: random
        var extracted = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1";
        generator.setDurableExecutionArn("arn:some-arn");
        generator.setExtractedTraceId(extracted);

        assertEquals(extracted, generator.generateTraceId());
    }

    @Test
    void generateTraceId_priorityOrder_arnDerivedWhenNoExtracted() {
        // Priority 2: ARN-derived when no extracted set
        generator.setDurableExecutionArn("arn:some-arn");

        var result = generator.generateTraceId();
        assertNotNull(result);
        assertEquals(32, result.length());

        // Should be deterministic
        assertEquals(result, generator.generateTraceId());
    }

    @Test
    void generateTraceId_priorityOrder_randomWhenNeitherSet() {
        // Priority 3: random when neither extracted nor ARN set
        var id1 = generator.generateTraceId();
        var id2 = generator.generateTraceId();

        assertNotNull(id1);
        assertEquals(32, id1.length());
        assertNotEquals(id1, id2, "Random IDs should differ");
    }

    @Test
    void setExtractedTraceId_withNull_clearsExtracted() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        generator.setDurableExecutionArn("arn:exec1");
        generator.setExtractedTraceId(xrayTraceId);

        assertEquals(xrayTraceId, generator.generateTraceId());

        // Clear extracted — should fall back to ARN-derived
        generator.setExtractedTraceId(null);
        var afterClear = generator.generateTraceId();
        assertNotEquals(xrayTraceId, afterClear, "Should fall back to ARN-derived after clearing extracted");
        assertEquals(32, afterClear.length());
    }

    @Test
    void setExtractedTraceId_simulatesMultipleInvocations_sameExecution() {
        // Simulates backend propagating same X-Ray Root across invocations
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";

        // First invocation
        generator.setExtractedTraceId(xrayTraceId);
        generator.setDurableExecutionArn("arn:exec1");
        var firstInvocation = generator.generateTraceId();

        // Second invocation (same execution, same X-Ray Root from backend)
        var generator2 = new DeterministicIdGenerator();
        generator2.setExtractedTraceId(xrayTraceId);
        generator2.setDurableExecutionArn("arn:exec1");
        var secondInvocation = generator2.generateTraceId();

        assertEquals(firstInvocation, secondInvocation, "Same X-Ray Root should produce same trace across invocations");
        assertEquals(xrayTraceId, firstInvocation);
    }

    @Test
    void setExtractedTraceId_validXrayFormat_32HexChars() {
        // Real-world format: X-Ray Root stripped and dashless
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        generator.setExtractedTraceId(xrayTraceId);

        var result = generator.generateTraceId();
        assertEquals(xrayTraceId, result);
        assertTrue(result.matches("[0-9a-f]{32}"));
    }
}
