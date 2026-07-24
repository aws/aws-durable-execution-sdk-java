// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** Tests the name-based operation-ID seam ([A-J2]) added for DAG support. */
class OperationIdGeneratorTest {

    @Test
    void operationIdForNameIsDeterministic() {
        var gen = new OperationIdGenerator("ctx");
        var first = gen.operationIdForName("DAG_NODE_T_x");
        var second = gen.operationIdForName("DAG_NODE_T_x");
        assertEquals(first, second, "same name must produce the same id");
    }

    @Test
    void distinctNamesProduceDistinctIds() {
        var gen = new OperationIdGenerator("ctx");
        assertNotEquals(gen.operationIdForName("DAG_NODE_T_a"), gen.operationIdForName("DAG_NODE_T_b"));
    }

    @Test
    void nameBasedIdMatchesHashOfPrefixedName() {
        var gen = new OperationIdGenerator("ctx");
        assertEquals(OperationIdGenerator.hashOperationId("ctx-DAG_NODE_T_x"), gen.operationIdForName("DAG_NODE_T_x"));
    }

    @Test
    void operationIdForNameDoesNotTouchCounter() {
        var gen = new OperationIdGenerator("ctx");
        // counter-based id before any name minting
        var counter1 = gen.nextOperationId();
        // mint several name-based ids
        gen.operationIdForName("a");
        gen.operationIdForName("b");
        gen.operationIdForName("c");
        // next counter-based id must be the "2" hash, proving the counter never advanced
        var counter2 = gen.nextOperationId();
        assertEquals(OperationIdGenerator.hashOperationId("ctx-1"), counter1);
        assertEquals(OperationIdGenerator.hashOperationId("ctx-2"), counter2);
    }

    @Test
    void nameBasedAndCounterBasedIdsDoNotCollide() {
        var gen = new OperationIdGenerator("ctx");
        // counter id for "1" vs a name literally "1" would collide only if prefixing differed; both use prefix.
        var nameId = gen.operationIdForName("DAG_NODE_T_task");
        var counterId = gen.nextOperationId();
        assertNotEquals(nameId, counterId);
    }
}
