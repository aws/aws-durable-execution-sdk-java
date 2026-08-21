// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static software.amazon.lambda.durable.execution.OperationIdGenerator.hashOperationId;

import org.junit.jupiter.api.Test;

class OperationIdGeneratorTest {
    @Test
    void customLocalIdUsesRootNamespaceAndAdvancesSequence() {
        var generator = new OperationIdGenerator(null);

        assertEquals(hashOperationId("node-a"), generator.nextOperationId("node-a"));
        assertEquals(hashOperationId("2"), generator.nextOperationId());
    }

    @Test
    void customLocalIdUsesParentNamespace() {
        var generator = new OperationIdGenerator("parent");

        assertEquals(hashOperationId("parent-node-a"), generator.nextOperationId("node-a"));
    }

    @Test
    void generatedIdsSkipClaimedNumericIds() {
        var generator = new OperationIdGenerator(null);

        assertEquals(hashOperationId("2"), generator.nextOperationId("2"));
        assertEquals(hashOperationId("3"), generator.nextOperationId());
    }

    @Test
    void customIdsCannotClaimGeneratedNumericIds() {
        var generator = new OperationIdGenerator(null);
        generator.nextOperationId();

        assertThrows(IllegalArgumentException.class, () -> generator.nextOperationId("1"));
        assertEquals(hashOperationId("2"), generator.nextOperationId());
    }

    @Test
    void duplicateCustomLocalIdsFailWithoutAdvancingSequence() {
        var generator = new OperationIdGenerator("parent");
        generator.nextOperationId("node");

        assertThrows(IllegalArgumentException.class, () -> generator.nextOperationId("node"));
        assertEquals(hashOperationId("parent-2"), generator.nextOperationId());
    }

    @Test
    void invalidCustomLocalIdsFailWithoutAdvancingSequence() {
        var generator = new OperationIdGenerator(null);

        assertThrows(NullPointerException.class, () -> generator.nextOperationId(null));
        assertThrows(IllegalArgumentException.class, () -> generator.nextOperationId(""));
        assertThrows(IllegalArgumentException.class, () -> generator.nextOperationId("  "));
        assertEquals(hashOperationId("1"), generator.nextOperationId());
    }
}
