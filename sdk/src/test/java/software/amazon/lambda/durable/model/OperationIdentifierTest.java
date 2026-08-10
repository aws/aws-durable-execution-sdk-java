// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static software.amazon.lambda.durable.model.OperationSubType.WAIT_FOR_CONDITION;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationType;

class OperationIdentifierTest {

    @Test
    void supportsExtensionDefinedSubtypeStrings() {
        var identifier = new OperationIdentifier("operation-1", "custom", OperationType.STEP, "CustomStep");

        assertEquals(OperationType.STEP, identifier.operationType());
        assertEquals("CustomStep", identifier.subType());
    }

    @Test
    void standardSubtypeFactoryStoresWireValue() {
        var identifier = OperationIdentifier.of("operation-1", "condition", WAIT_FOR_CONDITION);

        assertEquals(OperationType.STEP, identifier.operationType());
        assertEquals("WaitForCondition", identifier.subType());
    }

    @Test
    void rejectsBlankSubtype() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new OperationIdentifier("operation-1", "custom", OperationType.STEP, " "));

        assertEquals("subType cannot be blank", exception.getMessage());
    }
}
