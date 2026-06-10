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
}
